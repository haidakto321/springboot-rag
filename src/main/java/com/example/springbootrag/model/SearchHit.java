package com.example.springbootrag.model;

/** One search result row, shared by every backend. */
public record SearchHit(
        long id,
        String docId,
        int chunkIndex,
        String content,
        double score
) {}
