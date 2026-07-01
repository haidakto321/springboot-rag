package com.example.springbootrag.fusion;

import com.example.springbootrag.model.SearchHit;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class RrfFusionTest {

    private SearchHit hit(long id) {
        return new SearchHit(id, "doc", (int) id, "c" + id, null, null, 0.0);
    }

    @Test
    void documentRankedHighInBothListsWinsTop() {
        List<SearchHit> a = List.of(hit(1), hit(2), hit(3));
        List<SearchHit> b = List.of(hit(2), hit(1), hit(4));

        List<SearchHit> fused = new RrfFusion(60).fuse(List.of(a, b), 10);

        assertThat(fused).extracting(SearchHit::id).startsWith(2L, 1L);
        assertThat(fused).extracting(SearchHit::id).contains(3L, 4L);
    }

    @Test
    void scoreIsSumOfReciprocalRanks() {
        List<SearchHit> a = List.of(hit(1)); // rank 0
        List<SearchHit> fused = new RrfFusion(60).fuse(List.of(a), 10);
        assertThat(fused.get(0).score()).isEqualTo(1.0 / (60 + 1));
    }

    @Test
    void respectsTopK() {
        List<SearchHit> a = List.of(hit(1), hit(2), hit(3));
        assertThat(new RrfFusion(60).fuse(List.of(a), 2)).hasSize(2);
    }
}
