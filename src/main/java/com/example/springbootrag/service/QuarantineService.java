package com.example.springbootrag.service;

import com.example.springbootrag.guard.SecretScanner;
import com.example.springbootrag.repository.QuarantineRepository;
import com.example.springbootrag.security.SecurityProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The one place a document is moved into the holding pen, so the ordering is decided once.
 *
 * <p>Un-index FIRST, then record the hold. The reverse order fails unsafe: if Qdrant is down, the
 * pen row commits, the delete throws, and the indexed copy stays searchable while
 * {@code GET /quarantine} tells an operator the document is contained. This order fails safe - the
 * caller gets an error and nothing claims a containment that did not happen.
 */
@Service
public class QuarantineService {

    private static final Logger log = LoggerFactory.getLogger(QuarantineService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final QuarantineRepository pen;
    private final IngestService ingest;
    private final SecurityProperties securityProps;

    public QuarantineService(QuarantineRepository pen, IngestService ingest,
                             SecurityProperties securityProps) {
        this.pen = pen;
        this.ingest = ingest;
        this.securityProps = securityProps;
    }

    /**
     * Removes any indexed copy and records the hold.
     *
     * @param rawText the document in the form a release has to re-ingest: the markdown for an
     *                upload, the raw record JSON for a record
     */
    public void hold(long projectId, String docId, String origin, String sourceFile, String docType,
                     String rawText, List<String> requestedGroups,
                     List<SecretScanner.Finding> findings) {
        // A document that WAS indexed and is now unsafe must not stay searchable because its
        // previous version passed the scan. IngestService.delete goes to Qdrant first, then
        // Postgres, then the doc_edge rows and the registry - LEARNINGS section 13.
        ingest.delete(projectId, docId);
        pen.hold(projectId, new QuarantineRepository.Held(docId, origin, sourceFile, docType,
                rawText, findingsJson(findings), labels(requestedGroups), null));
        log.warn("document '{}' quarantined: {}", docId,
                findings.stream().map(SecretScanner.Finding::rule).toList());
    }

    /**
     * The label the document would have been indexed under, resolved the same way ingest resolves
     * it. Hardcoding "public" here would hold the document under a group that need not exist, and
     * {@code resolveGroups} would then reject it on release - a permanently unreleasable document.
     */
    private List<String> labels(List<String> requested) {
        return requested == null || requested.isEmpty()
                ? List.of(securityProps.getDefaultGroup()) : requested;
    }

    private static String findingsJson(List<SecretScanner.Finding> findings) {
        try {
            return MAPPER.writeValueAsString(findings);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise quarantine findings", e);
        }
    }
}
