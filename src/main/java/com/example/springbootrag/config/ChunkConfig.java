package com.example.springbootrag.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ChunkProperties.class)
public class ChunkConfig {
}
