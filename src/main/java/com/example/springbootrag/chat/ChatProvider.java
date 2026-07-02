package com.example.springbootrag.chat;

import java.util.List;
import java.util.function.Consumer;

/** Generates one assistant reply from a system + user prompt pair. Ollama now, Azure swap later. */
public interface ChatProvider {

    String chat(String systemPrompt, String userPrompt);

    /**
     * Streams an assistant reply token-by-token for a multi-turn conversation.
     * {@code messages} is the ordered turn history (system prompt passed separately);
     * {@code onToken} is called once per streamed content delta.
     */
    default void chatStream(String systemPrompt, List<ChatMessage> messages, Consumer<String> onToken) {
        throw new UnsupportedOperationException("streaming not supported by this provider");
    }

    /** One conversation turn. role is "system" | "user" | "assistant". */
    record ChatMessage(String role, String content) {}
}
