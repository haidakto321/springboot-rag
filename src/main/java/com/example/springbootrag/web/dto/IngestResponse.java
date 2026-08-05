package com.example.springbootrag.web.dto;

import java.util.List;

/**
 * Upload result. {@code warnings} carries ingest-time smells (currently prompt-injection
 * patterns) so the person uploading sees them while they are still looking at the screen.
 */
public record IngestResponse(String docId, int chunksStored, List<String> warnings) {

    public IngestResponse(String docId, int chunksStored) {
        this(docId, chunksStored, List.of());
    }
}
