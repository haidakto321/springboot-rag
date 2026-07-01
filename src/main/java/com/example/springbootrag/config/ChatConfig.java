package com.example.springbootrag.config;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.chat.OllamaChatProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(ChatProperties.class)
public class ChatConfig {

    @Bean
    public ChatProvider chatProvider(ChatProperties props,
                                     @Value("${app.ollama.base-url}") String baseUrl) {
        RestClient client = RestClient.builder().baseUrl(baseUrl).build();
        return new OllamaChatProvider(client, props);
    }
}
