package com.example.springbootrag.config;

import com.example.springbootrag.rerank.DjlReranker;
import com.example.springbootrag.rerank.IdentityReranker;
import com.example.springbootrag.rerank.Reranker;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RerankProperties.class)
public class RerankConfig {

    @Bean
    public Reranker reranker(RerankProperties props) {
        if ("djl".equalsIgnoreCase(props.getProvider())) {
            return new DjlReranker(props);
        }
        return new IdentityReranker();
    }
}
