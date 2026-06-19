package com.example.springbootrag.config;

import com.example.springbootrag.rerank.IdentityReranker;
import com.example.springbootrag.rerank.Reranker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RerankConfigTest {

    @Test
    void defaultsToIdentityWhenProviderUnset() {
        RerankProperties props = new RerankProperties();
        Reranker reranker = new RerankConfig().reranker(props);
        assertThat(reranker).isInstanceOf(IdentityReranker.class);
    }
}
