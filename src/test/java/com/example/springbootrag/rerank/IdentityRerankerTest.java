package com.example.springbootrag.rerank;

import com.example.springbootrag.model.SearchHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityRerankerTest {

    private SearchHit hit(long id) {
        return new SearchHit(id, "doc", (int) id, "c" + id, null, null, 0.0);
    }

    @Test
    void keepsOrderAndTrimsToTopK() {
        List<SearchHit> candidates = List.of(hit(1), hit(2), hit(3));
        List<SearchHit> out = new IdentityReranker().rerank("q", candidates, 2);
        assertThat(out).extracting(SearchHit::id).containsExactly(1L, 2L);
    }

    @Test
    void topKLargerThanInputReturnsAll() {
        List<SearchHit> candidates = List.of(hit(1), hit(2));
        List<SearchHit> out = new IdentityReranker().rerank("q", candidates, 10);
        assertThat(out).hasSize(2);
    }
}
