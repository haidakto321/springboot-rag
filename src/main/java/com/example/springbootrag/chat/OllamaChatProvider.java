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

    // qwen3 and other reasoning models emit a <think>...</think> block. We ask Ollama to disable
    // it (think:false + the /no_think soft-switch), and ALSO strip it defensively in case an older
    // Ollama or model ignores the flag - otherwise the raw chain-of-thought leaks into the answer.
    private static final String NO_THINK = "/no_think";
    private static final String THINK_OPEN = "<think>";
    private static final String THINK_CLOSE = "</think>";

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
                                    Map.of("role", "system", "content", systemPrompt + "\n" + NO_THINK),
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
        return stripThink(resp.message().content());
    }

    @Override
    public void chatStream(String systemPrompt, List<ChatMessage> messages, Consumer<String> onToken) {
        List<Map<String, String>> ollamaMessages = new ArrayList<>();
        ollamaMessages.add(Map.of("role", "system", "content", systemPrompt + "\n" + NO_THINK));
        for (ChatMessage m : messages) {
            ollamaMessages.add(Map.of("role", m.role(), "content", m.content()));
        }
        ThinkFilter filter = new ThinkFilter(onToken);
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
                                    filter.accept(chunk.message().content());
                                }
                                if (chunk.done()) break;
                            }
                        }
                        return null;
                    });
        } catch (ResourceAccessException e) {
            throw new ChatUnavailableException("chat model unavailable: " + e.getMessage(), e);
        }
        filter.flush();
    }

    /** Removes a complete or dangling &lt;think&gt; block from a full (non-streamed) reply. */
    static String stripThink(String text) {
        String cleaned = text.replaceAll("(?s)" + THINK_OPEN + ".*?" + THINK_CLOSE, "");
        int open = cleaned.indexOf(THINK_OPEN);            // unterminated think block (truncated output)
        if (open >= 0) cleaned = cleaned.substring(0, open);
        return cleaned.strip();
    }

    /**
     * Streaming filter that suppresses text inside &lt;think&gt;...&lt;/think&gt; and forwards the rest.
     * Buffers a few characters so a tag split across token boundaries is still detected.
     */
    static final class ThinkFilter {
        private final Consumer<String> out;
        private final StringBuilder buf = new StringBuilder();
        private boolean inThink = false;

        ThinkFilter(Consumer<String> out) { this.out = out; }

        void accept(String piece) {
            buf.append(piece);
            process(false);
        }

        void flush() {
            process(true);
            if (!inThink && buf.length() > 0) {
                out.accept(buf.toString());
                buf.setLength(0);
            }
        }

        private void process(boolean atEnd) {
            while (true) {
                if (inThink) {
                    int close = buf.indexOf(THINK_CLOSE);
                    if (close < 0) {
                        // still inside the think block: drop it, keep only a small tail for a split tag
                        if (!atEnd && buf.length() > THINK_CLOSE.length()) {
                            buf.delete(0, buf.length() - THINK_CLOSE.length());
                        }
                        return;
                    }
                    buf.delete(0, close + THINK_CLOSE.length());
                    inThink = false;
                } else {
                    int open = buf.indexOf(THINK_OPEN);
                    if (open < 0) {
                        // emit safe content, holding back a possible partial opening tag at the tail
                        int safe = atEnd ? buf.length() : Math.max(0, buf.length() - (THINK_OPEN.length() - 1));
                        if (safe > 0) {
                            out.accept(buf.substring(0, safe));
                            buf.delete(0, safe);
                        }
                        return;
                    }
                    if (open > 0) out.accept(buf.substring(0, open));
                    buf.delete(0, open + THINK_OPEN.length());
                    inThink = true;
                }
            }
        }
    }

    private record ChatResponse(Message message) {}
    private record StreamChunk(Message message, boolean done) {}
    private record Message(String role, String content) {}
}
