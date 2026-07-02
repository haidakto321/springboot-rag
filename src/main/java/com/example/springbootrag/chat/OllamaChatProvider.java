package com.example.springbootrag.chat;

import com.example.springbootrag.config.ChatProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class OllamaChatProvider implements ChatProvider {

    private final RestClient client;
    private final ChatProperties props;
    private final ObjectMapper mapper =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

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
                            "think", false,
                            "messages", List.of(
                                    Map.of("role", "system", "content", systemPrompt),
                                    Map.of("role", "user", "content", userPrompt))))
                    .retrieve()
                    .body(ChatResponse.class);
        } catch (RestClientResponseException e) {
            throw new ChatUnavailableException("Ollama returned HTTP " + e.getStatusCode() + ": " + e.getMessage(), e);
        } catch (ResourceAccessException e) {
            throw new ChatUnavailableException("chat model unavailable: " + e.getMessage(), e);
        }
        if (resp == null || resp.message() == null || resp.message().content() == null) {
            throw new ChatUnavailableException("Ollama returned no chat message");
        }
        return resp.message().content();
    }

    @Override
    public void chatStream(String systemPrompt, List<ChatMessage> messages, Consumer<String> onToken) {
        List<Map<String, String>> ollamaMessages = new ArrayList<>();
        ollamaMessages.add(Map.of("role", "system", "content", systemPrompt));
        for (ChatMessage m : messages) {
            ollamaMessages.add(Map.of("role", m.role(), "content", m.content()));
        }
        try {
            client.post()
                    .uri("/api/chat")
                    .body(Map.of(
                            "model", props.getModel(),
                            "stream", true,
                            "think", false,
                            "messages", ollamaMessages))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            throw new ChatUnavailableException("Ollama returned HTTP " + response.getStatusCode());
                        }
                        // Ollama streams newline-delimited JSON; one object per token, last has done=true.
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.isBlank()) continue;
                                StreamChunk chunk = mapper.readValue(line, StreamChunk.class);
                                if (chunk.message() != null && chunk.message().content() != null) {
                                    onToken.accept(chunk.message().content());
                                }
                                if (chunk.done()) break;
                            }
                        }
                        return null;
                    });
        } catch (ResourceAccessException e) {
            throw new ChatUnavailableException("chat model unavailable: " + e.getMessage(), e);
        }
    }

    private record ChatResponse(Message message) {}
    private record StreamChunk(Message message, boolean done) {}
    private record Message(String role, String content) {}
}
