package com.example.springbootrag.rerank;

import com.example.springbootrag.config.RerankProperties;
import com.example.springbootrag.model.SearchHit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "RUN_DJL_SPIKE", matches = "true")
class DjlRerankerManualTest {

    private SearchHit hit(long id, String content) {
        return new SearchHit(id, "doc", (int) id, content, 0.0);
    }

    @Test
    void reordersRelevantHitToTop() {
        RerankProperties props = new RerankProperties();
        props.setProvider("djl");
        DjlReranker reranker = new DjlReranker(props);

        List<SearchHit> candidates = List.of(
                hit(1, "The cafeteria menu changes every Monday."),
                hit(2, "Steps to restart the payment service following an incident."));

        List<SearchHit> out = reranker.rerank(
                "how to restart the payment service after an outage", candidates, 2);

        assertThat(out.get(0).id()).isEqualTo(2L);
    }
}
