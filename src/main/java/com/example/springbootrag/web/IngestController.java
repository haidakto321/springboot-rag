package com.example.springbootrag.web;

import com.example.springbootrag.service.IngestService;
import com.example.springbootrag.web.dto.IngestRequest;
import com.example.springbootrag.web.dto.IngestResponse;
import org.springframework.web.bind.annotation.*;

@RestController
public class IngestController {

    private final IngestService ingestService;

    public IngestController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping("/ingest")
    public IngestResponse ingest(@RequestBody IngestRequest req) {
        int stored = ingestService.ingest(req.docId(), req.text());
        return new IngestResponse(req.docId(), stored);
    }

    @DeleteMapping("/docs/{docId}")
    public void delete(@PathVariable String docId) {
        ingestService.delete(docId);
    }
}
