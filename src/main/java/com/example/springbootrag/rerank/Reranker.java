package com.example.springbootrag.rerank;

import com.example.springbootrag.model.SearchHit;

import java.util.List;

/** Reorders candidate hits by relevance to the query, then trims to topK. */
public interface Reranker {
    List<SearchHit> rerank(String query, List<SearchHit> candidates, int topK);
}
