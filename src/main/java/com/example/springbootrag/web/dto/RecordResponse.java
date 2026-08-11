package com.example.springbootrag.web.dto;

import com.example.springbootrag.guard.SecretScanner;

import java.util.List;

/**
 * {@code status} is one of: indexed, metadata-refreshed, skipped, quarantined.
 *
 * <p>{@code quarantined} means the rendered text tripped {@link SecretScanner} and nothing was
 * indexed; {@code findings} says why, with the values masked.
 */
public record RecordResponse(String docId, int chunksStored, String status, List<String> warnings,
                             List<SecretScanner.Finding> findings) {

    public RecordResponse(String docId, int chunksStored, String status, List<String> warnings) {
        this(docId, chunksStored, status, warnings, List.of());
    }
}
