package com.example.springbootrag.rerank;

import com.example.springbootrag.model.SearchHit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Test double: reverses candidate order, then trims to topK. Deterministic, no model. */
public class FakeReranker implements Reranker {

    @Override
    public List<SearchHit> rerank(String query, List<SearchHit> candidates, int topK) {
        List<SearchHit> copy = new ArrayList<>(candidates);
        Collections.reverse(copy);
        return copy.stream().limit(topK).toList();
    }
}
