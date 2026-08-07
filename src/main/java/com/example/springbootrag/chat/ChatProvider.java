package com.example.springbootrag.chat;

import java.util.List;
import java.util.function.Consumer;

/** Generates one assistant reply from a system + user prompt pair. Ollama now, Azure swap later. */
public interface ChatProvider {

    String chat(String systemPrompt, String userPrompt);

    /**
     * Same as {@link #chat}, but against a named model.
     *
     * <p>Exists so a cheap fast model can do query understanding while the large one writes the
     * answer - the model-tiering lever in RAG-MASTERY section 8. The default ignores the name, so a
     * provider that cannot switch models per call stays valid and simply uses its configured one.
     */
    default String chat(String systemPrompt, String userPrompt, String model) {
        return chat(systemPrompt, userPrompt);
    }

    /**
     * Same call as {@link #chat}, but also reporting how many tokens it cost.
     *
     * <p>Default implementation reports unknown usage, so a provider that cannot measure tokens
     * still satisfies the interface - the trace then records nulls rather than invented numbers.
     */
    default ChatReply chatDetailed(String systemPrompt, String userPrompt) {
        return new ChatReply(chat(systemPrompt, userPrompt), Usage.unknown());
    }

    /** Reply text plus token usage. */
    record ChatReply(String content, Usage usage) {}

    /** Null means the provider did not report it - never zero, which would read as "free". */
    record Usage(Integer promptTokens, Integer completionTokens) {
        public static Usage unknown() {
            return new Usage(null, null);
        }
    }

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

    /**
     * Streaming variant that also reports token usage once the final chunk arrives.
     *
     * <p>Defaults DOWN the chain (6-arg to 5-arg to 3-arg), so a provider that only implements the
     * simplest overload still works everywhere and simply reports unknown usage.
     */
    default void chatStream(String systemPrompt, List<ChatMessage> messages, boolean think,
                            Consumer<String> onToken, Consumer<String> onReasoning,
                            Consumer<Usage> onUsage) {
        chatStream(systemPrompt, messages, think, onToken, onReasoning);
        onUsage.accept(Usage.unknown());
    }

    /** One conversation turn. role is "system" | "user" | "assistant". */
    record ChatMessage(String role, String content) {}
}
