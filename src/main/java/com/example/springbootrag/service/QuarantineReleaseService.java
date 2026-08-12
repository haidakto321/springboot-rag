package com.example.springbootrag.service;

import com.example.springbootrag.repository.QuarantineAuditRepository;
import com.example.springbootrag.repository.QuarantineRepository;
import com.example.springbootrag.security.CurrentUser;
import com.example.springbootrag.security.Roles;
import com.example.springbootrag.web.dto.RecordRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * The two ways a hold ends, and the audit ordering that makes a half-finished one visible.
 *
 * <p>The decision row is written BEFORE the ingest runs and stamped after. A release that dies
 * between the two - the process is killed, the box loses power - leaves a row reading
 * {@code attempted}, which is a queryable signal that a release started and never finished. A row
 * written afterwards would record nothing at all in exactly the case most worth recording. An
 * exception the service can see is stamped {@code failed} and rethrown, so the two outcomes do not
 * blur: {@code failed} is a decision the system reached, {@code attempted} is one nobody finished.
 *
 * <p>Release deliberately does NOT re-scan. Re-running the rule that held the document would refuse
 * the exact document a person just decided to accept; the human decision IS the override, and it is
 * now recorded in {@code quarantine_audit} rather than implied by the pen row disappearing.
 *
 * <p>Separate from {@link QuarantineService} because release needs {@link RecordIngestService},
 * which already injects that class - the merged version is a constructor-injection cycle.
 *
 * <p>Both checks live HERE, on the methods they protect, rather than on the controller that calls
 * them: the role via {@code @PreAuthorize}, and the group scoping via the lookup below. An earlier
 * version took an already-resolved {@code Held} and trusted the controller to have done both, which
 * meant any future injector of this service would have bypassed the entire control with no compile
 * error - the same "a control at the callers is a control you will bypass" shape as LEARNINGS §22.
 * The controller keeps its own {@code @PreAuthorize} so the HTTP contract is legible and a refusal
 * costs no database work; this one is the control.
 */
@Service
public class QuarantineReleaseService {

    private static final Logger log = LoggerFactory.getLogger(QuarantineReleaseService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final QuarantineRepository pen;
    private final QuarantineAuditRepository audit;
    private final IngestService ingest;
    private final RecordIngestService records;
    private final CurrentUser currentUser;

    public QuarantineReleaseService(QuarantineRepository pen, QuarantineAuditRepository audit,
                                    IngestService ingest, RecordIngestService records,
                                    CurrentUser currentUser) {
        this.pen = pen;
        this.audit = audit;
        this.ingest = ingest;
        this.records = records;
        this.currentUser = currentUser;
    }

    /** Indexes the held document under the labels its original ingest carried, then empties the pen. */
    @PreAuthorize("hasRole('" + Roles.QUARANTINE_RELEASE + "')")
    public void release(long projectId, String docId) {
        QuarantineRepository.Held held = require(projectId, docId);
        long id = begin(projectId, held, QuarantineAuditRepository.ACTION_RELEASE);
        try {
            if ("record".equals(held.origin())) {
                records.ingestReleased(projectId, toRequest(held));
            } else {
                // scanForSecrets = false: re-running the rule that held it would refuse the exact
                // document a human just decided to accept.
                ingest.ingestMarkdown(projectId, held.docId(), held.sourceFile(), held.rawText(),
                        null, held.allowedGroups(), false);
            }
            pen.drop(projectId, held.docId());
        } catch (Throwable t) {
            // Throwable, not RuntimeException: an OutOfMemoryError mid-ingest is exactly the case
            // where the row must not be left claiming the release never got past 'attempted'.
            fail(id, t);
            throw t;
        }
        succeed(id);
    }

    /** Throws the held document away. Irreversible: the pen holds the only copy. */
    @PreAuthorize("hasRole('" + Roles.QUARANTINE_RELEASE + "')")
    public void discard(long projectId, String docId) {
        QuarantineRepository.Held held = require(projectId, docId);
        long id = begin(projectId, held, QuarantineAuditRepository.ACTION_DISCARD);
        try {
            pen.drop(projectId, held.docId());
        } catch (Throwable t) {
            fail(id, t);
            throw t;
        }
        succeed(id);
    }

    /**
     * Looks the row up THROUGH the caller's groups, so releasing or discarding something you cannot
     * read is not expressible - the same rule the read path follows, and independent of the role.
     */
    private QuarantineRepository.Held require(long projectId, String docId) {
        return pen.find(currentUser.context(), projectId, docId)
                .orElseThrow(() -> new IllegalArgumentException("nothing held under: " + docId));
    }

    private long begin(long projectId, QuarantineRepository.Held held, String action) {
        return audit.record(projectId, held.docId(), action,
                QuarantineAuditRepository.OUTCOME_ATTEMPTED, currentUser.context().principal(),
                held.findingsJson(), held.allowedGroups());
    }

    /**
     * Stamps the failure without ever replacing the real one: if the stamp itself throws, that
     * exception is attached as suppressed and the original still reaches the caller and the logs.
     */
    private void fail(long id, Throwable original) {
        try {
            audit.outcome(id, QuarantineAuditRepository.OUTCOME_FAILED);
        } catch (RuntimeException stampFailure) {
            original.addSuppressed(stampFailure);
        }
    }

    /**
     * Logged, never thrown. The act has already committed by this point - the document is indexed
     * and the pen row is gone - so throwing here would report a failure that did not happen and
     * leave the caller believing a completed release did not complete. The cost of swallowing is a
     * row reading {@code attempted} for a release that succeeded, which is the direction of error
     * an operator can investigate.
     */
    private void succeed(long id) {
        try {
            audit.outcome(id, QuarantineAuditRepository.OUTCOME_OK);
        } catch (RuntimeException e) {
            log.error("release/discard completed but its audit row {} could not be stamped ok", id, e);
        }
    }

    private RecordRequest toRequest(QuarantineRepository.Held h) {
        try {
            // force=true: the registry row was dropped when the record was held, but a release must
            // re-index even if some other path left a matching hash behind.
            return new RecordRequest(h.docId(), h.docType(), MAPPER.readTree(h.rawText()), null,
                    h.allowedGroups(), Boolean.TRUE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("held record is not valid JSON: " + h.docId(), e);
        }
    }
}
