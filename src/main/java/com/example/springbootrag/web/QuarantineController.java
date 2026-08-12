package com.example.springbootrag.web;

import com.example.springbootrag.repository.QuarantineRepository;
import com.example.springbootrag.security.CurrentUser;
import com.example.springbootrag.security.Roles;
import com.example.springbootrag.service.QuarantineReleaseService;
import com.example.springbootrag.web.dto.QuarantineView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * refuse the exact document a person just decided to accept; the human decision IS the override.
 *
 * <p>Reading what is held stays open to anyone whose groups overlap the document - the findings are
 * masked, and an uploader seeing that their own upload was held is the only feedback they get.
 * Releasing and discarding need {@link Roles#QUARANTINE_RELEASE}, because both undo the one
 * blocking control this system has.
 *
 * <p>The annotations here fail a refusal at the edge, before any database work. They are NOT the
 * control: {@link QuarantineReleaseService} carries the same check plus the group-scoped lookup, so
 * the rule cannot be bypassed by reaching that service some other way.
 */
@RestController
public class QuarantineController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final QuarantineRepository pen;
    private final QuarantineReleaseService releaseService;
    private final CurrentUser currentUser;

    public QuarantineController(QuarantineRepository pen, QuarantineReleaseService releaseService,
                                CurrentUser currentUser) {
        this.pen = pen;
        this.releaseService = releaseService;
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
    @PreAuthorize("hasRole('" + Roles.QUARANTINE_RELEASE + "')")
    public void release(@PathVariable long projectId, @PathVariable String docId) {
        releaseService.release(projectId, docId);
    }

    @DeleteMapping("/projects/{projectId}/quarantine/{docId}")
    @PreAuthorize("hasRole('" + Roles.QUARANTINE_RELEASE + "')")
    public void discard(@PathVariable long projectId, @PathVariable String docId) {
        releaseService.discard(projectId, docId);
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
