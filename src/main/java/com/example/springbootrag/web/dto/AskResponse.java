package com.example.springbootrag.web.dto;

import java.util.List;

/**
 * RAG answer plus the chunks it was generated from.
 *
 * @param appliedFilter the metadata filter actually used, in the same shape the API accepts, so a
 *                      client can echo it straight back as an explicit filter. Null when none.
 * @param widened       true when that filter matched nothing and retrieval was retried without it
 */
public record AskResponse(String answer, List<Source> sources,
                          Object appliedFilter, boolean widened) {

    /** Pre-filter callers: no filter, not widened. */
    public AskResponse(String answer, List<Source> sources) {
        this(answer, sources, null, false);
    }

    public record Source(int index, String docId, String headingPath, double score, String content, int chunkIndex) {}
}
