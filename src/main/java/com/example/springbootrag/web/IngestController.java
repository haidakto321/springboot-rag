package com.example.springbootrag.web;

import com.example.springbootrag.guard.QuarantineRequiredException;
import com.example.springbootrag.service.IngestService;
import com.example.springbootrag.service.ProjectService;
import com.example.springbootrag.service.QuarantineService;
import com.example.springbootrag.web.dto.IngestRequest;
import com.example.springbootrag.web.dto.IngestResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class IngestController {

    private final IngestService ingestService;
    private final ProjectService projectService;
    private final QuarantineService quarantineService;

    public IngestController(IngestService ingestService, ProjectService projectService,
                            QuarantineService quarantineService) {
        this.ingestService = ingestService;
        this.projectService = projectService;
        this.quarantineService = quarantineService;
    }

    /**
     * Raw-text ingest. Holds the text instead of indexing it when it carries a credential - this
     * endpoint reached the index without any scan until a review found it, which is the reason the
     * scan now lives in {@link IngestService} rather than in each caller.
     */
    @PostMapping("/ingest")
    public IngestResponse ingest(@RequestBody IngestRequest req) {
        long projectId = projectService.defaultProjectId();
        try {
            int stored = ingestService.ingest(req.docId(), req.text());
            return new IngestResponse(req.docId(), stored);
        } catch (QuarantineRequiredException e) {
            quarantineService.hold(projectId, req.docId(), "upload", null, null, req.text(),
                    null, e.findings());
            return new IngestResponse(req.docId(), 0, List.of(), true, e.findings());
        }
    }

    @DeleteMapping("/docs/{docId}")
    public void delete(@PathVariable String docId) {
        ingestService.delete(docId);
    }
}
