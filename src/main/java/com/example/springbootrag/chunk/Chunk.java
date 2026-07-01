package com.example.springbootrag.chunk;

/** One ingestible chunk: text to index, optional heading breadcrumb, position within the doc. */
public record Chunk(String text, String headingPath, int position) {}
