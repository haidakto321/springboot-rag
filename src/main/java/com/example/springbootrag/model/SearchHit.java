package com.example.springbootrag.model;

import java.time.Instant;

/** One search result row, shared by every backend. Metadata fields are null for pre-metadata rows. */
public record SearchHit(
        long id,
        String docId,
        int chunkIndex,
        String content,
        String sourceFile,
        String headingPath,
        double score,
        Instant updatedAt
) {}
