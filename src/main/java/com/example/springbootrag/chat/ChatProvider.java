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

    /**
     * Streaming variant that separates the model's reasoning from the answer.
     * When {@code think} is true the model is asked to reason first; reasoning deltas go to
     * {@code onReasoning} and answer deltas to {@code onToken}. Providers that do not distinguish
     * reasoning fall back to the plain {@link #chatStream(String, List, Consumer)}.
     */
    default void chatStream(String systemPrompt, List<ChatMessage> messages, boolean think,
                            Consumer<String> onToken, Consumer<String> onReasoning) {
        chatStream(systemPrompt, messages, onToken);
    }

    /** One conversation turn. role is "system" | "user" | "assistant". */
    record ChatMessage(String role, String content) {}
}
