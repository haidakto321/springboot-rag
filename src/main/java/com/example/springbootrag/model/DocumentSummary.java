package com.example.springbootrag.model;

/** One ingested document: id, original filename (null for raw-text ingest), chunk count. */
public record DocumentSummary(String docId, String sourceFile, int chunkCount) {}
