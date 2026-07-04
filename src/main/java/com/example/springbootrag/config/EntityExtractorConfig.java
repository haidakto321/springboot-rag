package com.example.springbootrag.config;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.graph.EntityExtractor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EntityExtractorConfig {

    @Bean
    public EntityExtractor entityExtractor(ChatProvider chat, GraphProperties props) {
        return new EntityExtractor(chat, props.getExtractModel());
    }
}
