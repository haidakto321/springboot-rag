package com.example.springbootrag.chat;

import com.example.springbootrag.config.ChatProperties;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

public class OllamaChatProvider implements ChatProvider {

    private final RestClient client;
    private final ChatProperties props;

    public OllamaChatProvider(RestClient client, ChatProperties props) {
        this.client = client;
        this.props = props;
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        ChatResponse resp;
        try {
            resp = client.post()
                    .uri("/api/chat")
                    .body(Map.of(
                            "model", props.getModel(),
                            "stream", false,
                            "messages", List.of(
                                    Map.of("role", "system", "content", systemPrompt),
                                    Map.of("role", "user", "content", userPrompt))))
                    .retrieve()
                    .body(ChatResponse.class);
        } catch (ResourceAccessException e) {
            throw new ChatUnavailableException("chat model unavailable: " + e.getMessage(), e);
        }
        if (resp == null || resp.message() == null || resp.message().content() == null) {
            throw new ChatUnavailableException("Ollama returned no chat message");
        }
        return resp.message().content();
    }

    private record ChatResponse(Message message) {}
    private record Message(String role, String content) {}
}
