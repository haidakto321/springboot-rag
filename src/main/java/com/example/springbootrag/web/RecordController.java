package com.example.springbootrag.web;

import com.example.springbootrag.security.CurrentUser;
import com.example.springbootrag.service.IngestService;
import com.example.springbootrag.service.ProjectService;
import com.example.springbootrag.service.RecordIngestService;
import com.example.springbootrag.web.dto.RecordRequest;
import com.example.springbootrag.web.dto.RecordResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ingest for extraction-pipeline output. Access labels work exactly as on the markdown path:
 * extracted text is still untrusted material, and a caller may only label a document with groups
 * they belong to.
 */
@RestController
public class RecordController {

    private final RecordIngestService recordIngest;
    private final IngestService ingestService;
    private final ProjectService projectService;
    private final CurrentUser currentUser;

    public RecordController(RecordIngestService recordIngest, IngestService ingestService,
                            ProjectService projectService, CurrentUser currentUser) {
        this.recordIngest = recordIngest;
        this.ingestService = ingestService;
        this.projectService = projectService;
        this.currentUser = currentUser;
    }

    @PostMapping(value = "/projects/{projectId}/records", consumes = MediaType.APPLICATION_JSON_VALUE)
    public RecordResponse ingest(@PathVariable long projectId, @RequestBody RecordRequest req) {
        requireProject(projectId);
        currentUser.requireOwnGroups(req == null ? null : req.groups());
        return recordIngest.ingest(projectId, req);
    }

    @DeleteMapping("/projects/{projectId}/records/{docId}")
    public void delete(@PathVariable long projectId, @PathVariable String docId) {
        requireProject(projectId);
        ingestService.delete(projectId, docId);
    }

    private void requireProject(long projectId) {
        if (!projectService.exists(projectId)) {
            throw new IllegalArgumentException("project not found: " + projectId);
        }
    }
}
