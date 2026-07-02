package com.example.springbootrag.web.dto;

/** One stored chunk of a document, for the chunk-view endpoint. headingPath may be null. */
public record ChunkView(int index, String headingPath, String content) {}
