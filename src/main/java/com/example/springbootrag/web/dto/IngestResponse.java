package com.example.springbootrag.web.dto;

import com.example.springbootrag.guard.SecretScanner;

import java.util.List;

/**
 * Upload result. {@code warnings} carries ingest-time smells (prompt-injection phrasings) so the
 * person uploading sees them while they are still looking at the screen.
 *
 * <p>{@code quarantined} is the harder outcome: the document tripped {@link SecretScanner} and was
 * NOT indexed. {@code findings} says why, with the values masked - a response that reprinted the
 * secret would move it from one place it should not be into two.
 */
public record IngestResponse(String docId, int chunksStored, List<String> warnings,
                             boolean quarantined, List<SecretScanner.Finding> findings) {

    public IngestResponse(String docId, int chunksStored) {
        this(docId, chunksStored, List.of(), false, List.of());
    }

    public IngestResponse(String docId, int chunksStored, List<String> warnings) {
        this(docId, chunksStored, warnings, false, List.of());
    }
}
