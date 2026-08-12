package com.example.springbootrag.service;

import com.example.springbootrag.guard.SecretScanner;
import com.example.springbootrag.repository.QuarantineAuditRepository;
import com.example.springbootrag.repository.QuarantineRepository;
import com.example.springbootrag.security.CurrentUser;
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
    private final QuarantineAuditRepository audit;
    private final IngestService ingest;
    private final SecurityProperties securityProps;
    private final CurrentUser currentUser;

    public QuarantineService(QuarantineRepository pen, QuarantineAuditRepository audit,
                             IngestService ingest, SecurityProperties securityProps,
                             CurrentUser currentUser) {
        this.pen = pen;
        this.audit = audit;
        this.ingest = ingest;
        this.securityProps = securityProps;
        this.currentUser = currentUser;
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
        hold(projectId, docId, origin, sourceFile, docType, rawText, requestedGroups, findings,
                currentUser.principalOrNull());
    }

    /**
     * Same, with the principal supplied by the caller.
     *
     * <p>Needed by any path that holds a document off the request thread. {@code /import-wiki}
     * returns a {@code StreamingResponseBody}, whose body runs on a thread where the
     * {@link org.springframework.security.core.context.SecurityContextHolder} thread-local is
     * empty - so resolving the principal here would record "nobody" for every hold on the bulk
     * import, which is the path most likely to meet a real credential. The caller resolves it while
     * the request thread still holds the context and passes it down.
     */
    public void hold(long projectId, String docId, String origin, String sourceFile, String docType,
                     String rawText, List<String> requestedGroups,
                     List<SecretScanner.Finding> findings, String principal) {
        String findingsJson = findingsJson(findings);
        List<String> labels = labels(requestedGroups);
        // A document that WAS indexed and is now unsafe must not stay searchable because its
        // previous version passed the scan. IngestService.delete goes to Qdrant first, then
        // Postgres, then the doc_edge rows and the registry - LEARNINGS section 13.
        ingest.delete(projectId, docId);
        pen.hold(projectId, new QuarantineRepository.Held(docId, origin, sourceFile, docType,
                rawText, findingsJson, labels, null));
        // Written AFTER the hold, unlike release. The ordering argument above applies one level up:
        // a row claiming containment before the un-index succeeded would assert something untrue.
        // And while the document sits in the pen, the pen row IS the durable record - the audit's
        // job only starts when that row is deleted.
        //
        // Logged, never thrown. By this point the containment has ALREADY succeeded and committed,
        // so letting a failed audit insert propagate would turn a successful quarantine into an
        // error at the caller - and WikiImporter calls this from inside a catch block, where a
        // sibling catch cannot catch it, so one audit failure would abort an entire bulk import at
        // its first held page. A missing history row is worse than nothing; an aborted import that
        // reports containment failure it did not have is worse still.
        try {
            audit.record(projectId, docId, QuarantineAuditRepository.ACTION_HELD,
                    QuarantineAuditRepository.OUTCOME_OK, principal, findingsJson, labels);
        } catch (RuntimeException e) {
            log.error("document '{}' was quarantined but the audit row could not be written", docId, e);
        }
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
