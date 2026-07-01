package com.example.springbootrag.web.dto;

import java.util.List;

/** RAG answer plus the chunks it was generated from. */
public record AskResponse(String answer, List<Source> sources) {

    public record Source(int index, String docId, String headingPath, double score, String content) {}
}
