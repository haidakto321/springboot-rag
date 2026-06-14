package com.example.springbootrag.config;

import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.embedding.OllamaEmbeddingProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class EmbeddingConfig {

    @Bean
    public EmbeddingProvider embeddingProvider(EmbeddingProperties props,
                                               @Value("${app.ollama.base-url}") String baseUrl) {
        RestClient client = RestClient.builder().baseUrl(baseUrl).build();
        return new OllamaEmbeddingProvider(client, props);
    }
}
