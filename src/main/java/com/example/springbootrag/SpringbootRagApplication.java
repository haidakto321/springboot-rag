package com.example.springbootrag;

import com.example.springbootrag.config.EmbeddingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(EmbeddingProperties.class)
public class SpringbootRagApplication {
    public static void main(String[] args) {
        SpringApplication.run(SpringbootRagApplication.class, args);
    }
}
