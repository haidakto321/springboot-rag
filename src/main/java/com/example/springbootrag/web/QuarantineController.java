package com.example.springbootrag.web;

import com.example.springbootrag.repository.QuarantineRepository;
import com.example.springbootrag.security.CurrentUser;
import com.example.springbootrag.service.IngestService;
import com.example.springbootrag.service.RecordIngestService;
import com.example.springbootrag.web.dto.QuarantineView;
import com.example.springbootrag.web.dto.RecordRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * The human end of quarantine: see what is held, and decide.
 *
 * <p>Release deliberately does NOT re-scan. Re-running the rule that held the document would
 * refuse the exact document a person just decided to accept; the human decision IS the override,
 * and it is recorded by the row leaving the pen.
 */
@RestController
public class QuarantineController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final QuarantineRepository pen;
    private final IngestService ingest;
    private final RecordIngestService records;
    private final CurrentUser currentUser;

    public QuarantineController(QuarantineRepository pen, IngestService ingest,
                                RecordIngestService records, CurrentUser currentUser) {
        this.pen = pen;
        this.ingest = ingest;
        this.records = records;
        this.currentUser = currentUser;
    }

    @GetMapping("/projects/{projectId}/quarantine")
    public List<QuarantineView> list(@PathVariable long projectId) {
        List<QuarantineView> out = new ArrayList<>();
        for (QuarantineRepository.Held h : pen.list(currentUser.context(), projectId)) {
            out.add(new QuarantineView(h.docId(), h.origin(), h.sourceFile(), h.docType(),
                    h.allowedGroups(), readFindings(h.findingsJson()), h.createdAt()));
        }
        return out;
    }

    /** Indexes the held document under the labels its original ingest carried, then empties the pen. */
    @PostMapping("/projects/{projectId}/quarantine/{docId}/release")
    public void release(@PathVariable long projectId, @PathVariable String docId) {
        QuarantineRepository.Held h = require(projectId, docId);
        if ("record".equals(h.origin())) {
            records.ingestReleased(projectId, toRequest(h));
        } else {
            // scanForSecrets = false: re-running the rule that held it would refuse the exact
            // document a human just decided to accept.
            ingest.ingestMarkdown(projectId, h.docId(), h.sourceFile(), h.rawText(), null,
                    h.allowedGroups(), false);
        }
        pen.drop(projectId, docId);
    }

    @DeleteMapping("/projects/{projectId}/quarantine/{docId}")
    public void discard(@PathVariable long projectId, @PathVariable String docId) {
        require(projectId, docId);
        pen.drop(projectId, docId);
    }

    /**
     * Looks the row up THROUGH the caller's groups, so releasing or discarding something you
     * cannot read is not expressible - the same rule the read path follows.
     */
    private QuarantineRepository.Held require(long projectId, String docId) {
        return pen.find(currentUser.context(), projectId, docId)
                .orElseThrow(() -> new IllegalArgumentException("nothing held under: " + docId));
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

    /** Findings are stored as JSON text; hand them back as JSON, not as an escaped string. */
    private Object readFindings(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
