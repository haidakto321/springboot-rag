package com.example.springbootrag.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QdrantConfig {

    @Bean
    public QdrantClient qdrantClient(@Value("${app.qdrant.host}") String host,
                                     @Value("${app.qdrant.port}") int port) {
        return new QdrantClient(
                QdrantGrpcClient.newBuilder(host, port, false).build());
    }
}
