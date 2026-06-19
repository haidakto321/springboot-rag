package com.example.springbootrag.rerank;

import com.example.springbootrag.model.SearchHit;

import java.util.List;

/** No-op reranker: keeps incoming order, just trims to topK. Default when no model is configured. */
public class IdentityReranker implements Reranker {

    @Override
    public List<SearchHit> rerank(String query, List<SearchHit> candidates, int topK) {
        return candidates.stream().limit(topK).toList();
    }
}
