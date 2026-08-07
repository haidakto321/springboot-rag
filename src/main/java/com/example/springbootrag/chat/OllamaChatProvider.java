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

    // qwen3 and other reasoning models emit a <think>...</think> block. Both paths now ask for
    // think:true so the reasoning arrives in its own field; the tag stripping below stays as a
    // defence for models or Ollama versions that leak it into content anyway. (The old
    // think:false + "/no_think" soft-switch made this WORSE - see LEARNINGS section 12.)
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

    /**
     * Non-streaming reply. Uses {@code think:true} for the same reason the streaming path does:
     * a reasoning model reasons either way, and asking it NOT to only moves the chain-of-thought
     * into {@code content}, where it pollutes the answer and defeats any check that parses it
     * (citations, refusal detection). With think:true the reasoning arrives in its own field and
     * is dropped here.
     */
    @Override
    public String chat(String systemPrompt, String userPrompt) {
        return chatDetailed(systemPrompt, userPrompt).content();
    }

    /** Model tiering: a blank name means "whatever app.chat.model says". */
    @Override
    public String chat(String systemPrompt, String userPrompt, String model) {
        return chatDetailed(systemPrompt, userPrompt,
                model == null || model.isBlank() ? props.getModel() : model).content();
    }

    @Override
    public ChatReply chatDetailed(String systemPrompt, String userPrompt) {
        return chatDetailed(systemPrompt, userPrompt, props.getModel());
    }

    private ChatReply chatDetailed(String systemPrompt, String userPrompt, String model) {
        ChatResponse resp;
        try {
            resp = client.post()
                    .uri("/api/chat")
                    .body(Map.of(
                            "model", model,
                            "stream", false,
                            "think", true,
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
        // Ollama names them prompt_eval_count / eval_count; note eval_count INCLUDES the tokens
        // spent thinking, which is exactly why the cost of a reasoning model is worth recording.
        return new ChatReply(stripThink(resp.message().content()),
                new Usage(resp.promptEvalCount(), resp.evalCount()));
    }

    @Override
    public void chatStream(String systemPrompt, List<ChatMessage> messages, Consumer<String> onToken) {
        chatStream(systemPrompt, messages, false, onToken, r -> {});
    }

    @Override
    public void chatStream(String systemPrompt, List<ChatMessage> messages, boolean think,
                           Consumer<String> onToken, Consumer<String> onReasoning) {
        chatStream(systemPrompt, messages, think, onToken, onReasoning, u -> {});
    }

    @Override
    public void chatStream(String systemPrompt, List<ChatMessage> messages, boolean think,
                           Consumer<String> onToken, Consumer<String> onReasoning,
                           Consumer<Usage> onUsage) {
        List<Map<String, String>> ollamaMessages = new ArrayList<>();
        ollamaMessages.add(Map.of("role", "system", "content", systemPrompt));
        for (ChatMessage m : messages) {
            ollamaMessages.add(Map.of("role", m.role(), "content", m.content()));
        }
        // Always ask Ollama to think: a reasoning model (qwen3) reasons regardless, and think:true
        // routes that reasoning to a separate 'thinking' field so it never leaks into the answer
        // content. The caller's `think` flag only decides whether we FORWARD it to onReasoning.
        Consumer<String> reasoningSink = think ? onReasoning : r -> {};
        ThinkFilter filter = new ThinkFilter(onToken, reasoningSink);
        try {
            client.post()
                    .uri("/api/chat")
                    .body(Map.of(
                            "model", props.getModel(),
                            "stream", true,
                            "think", true,
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
                                if (chunk.message() != null) {
                                    // Modern Ollama returns reasoning in a separate 'thinking' field
                                    // (content is empty while thinking). Forward it as reasoning.
                                    String thinking = chunk.message().thinking();
                                    if (thinking != null && !thinking.isEmpty()) {
                                        reasoningSink.accept(thinking);
                                    }
                                    // content carries the answer; ThinkFilter also defensively strips
                                    // any inline <think> tags a model might still leak into content.
                                    if (chunk.message().content() != null) {
                                        filter.accept(chunk.message().content());
                                    }
                                }
                                if (chunk.done()) {
                                    // Counts only arrive on the final chunk.
                                    onUsage.accept(new Usage(chunk.promptEvalCount(), chunk.evalCount()));
                                    break;
                                }
                            }
                        }
                        return null;
                    });
        } catch (ResourceAccessException e) {
            throw new ChatUnavailableException("chat model unavailable: " + e.getMessage(), e);
        }
        filter.flush();
    }

    /**
     * Removes a complete, dangling, or unterminated &lt;think&gt; block from a full (non-streamed)
     * reply. The dangling case - a closing tag with no opening one - is what a small model
     * produces when it leaks chain-of-thought straight into content; everything before that tag is
     * reasoning, not answer.
     */
    static String stripThink(String text) {
        String cleaned = text.replaceAll("(?s)" + THINK_OPEN + ".*?" + THINK_CLOSE, "");
        int dangling = cleaned.indexOf(THINK_CLOSE);       // </think> with no opener: drop the lead-in
        if (dangling >= 0) cleaned = cleaned.substring(dangling + THINK_CLOSE.length());
        int open = cleaned.indexOf(THINK_OPEN);            // unterminated think block (truncated output)
        if (open >= 0) cleaned = cleaned.substring(0, open);
        return cleaned.strip();
    }

    /**
     * Streaming filter that separates a &lt;think&gt;...&lt;/think&gt; reasoning block from the answer:
     * reasoning deltas go to {@code reasoningOut}, everything else to {@code answerOut}. Buffers a
     * few characters so a tag split across token boundaries is still detected. Also captures a
     * DANGLING &lt;/think&gt; with no opening tag (some small models leak chain-of-thought that way)
     * by routing the text before it to reasoning instead of leaking it into the answer.
     */
    static final class ThinkFilter {
        private final Consumer<String> answerOut;
        private final Consumer<String> reasoningOut;
        private final StringBuilder buf = new StringBuilder();
        private boolean inThink = false;
        // Hold back enough tail (while mid-stream) to detect either tag split across token boundaries.
        private static final int TAIL = Math.max(THINK_OPEN.length(), THINK_CLOSE.length()) - 1;

        ThinkFilter(Consumer<String> answerOut, Consumer<String> reasoningOut) {
            this.answerOut = answerOut;
            this.reasoningOut = reasoningOut;
        }

        void accept(String piece) {
            buf.append(piece);
            process(false);
        }

        void flush() {
            process(true);
            if (buf.length() > 0) {
                (inThink ? reasoningOut : answerOut).accept(buf.toString());
                buf.setLength(0);
            }
        }

        private void process(boolean atEnd) {
            while (true) {
                if (inThink) {
                    int close = buf.indexOf(THINK_CLOSE);
                    if (close < 0) {
                        // still inside the think block: forward it as reasoning, keep a small tail
                        int keep = atEnd ? 0 : Math.min(buf.length(), TAIL);
                        int emit = buf.length() - keep;
                        if (emit > 0) { reasoningOut.accept(buf.substring(0, emit)); buf.delete(0, emit); }
                        return;
                    }
                    if (close > 0) reasoningOut.accept(buf.substring(0, close));
                    buf.delete(0, close + THINK_CLOSE.length());
                    inThink = false;
                } else {
                    int open = buf.indexOf(THINK_OPEN);
                    int close = buf.indexOf(THINK_CLOSE);
                    // Dangling </think> before any <think>: preceding text is leaked reasoning.
                    if (close >= 0 && (open < 0 || close < open)) {
                        if (close > 0) reasoningOut.accept(buf.substring(0, close));
                        buf.delete(0, close + THINK_CLOSE.length());
                        continue;
                    }
                    if (open < 0) {
                        // emit answer, holding back a tail that could be the start of either tag
                        int keep = atEnd ? 0 : Math.min(buf.length(), TAIL);
                        int emit = buf.length() - keep;
                        if (emit > 0) { answerOut.accept(buf.substring(0, emit)); buf.delete(0, emit); }
                        return;
                    }
                    if (open > 0) answerOut.accept(buf.substring(0, open));
                    buf.delete(0, open + THINK_OPEN.length());
                    inThink = true;
                }
            }
        }
    }

    private record ChatResponse(Message message,
                                @com.fasterxml.jackson.annotation.JsonProperty("prompt_eval_count") Integer promptEvalCount,
                                @com.fasterxml.jackson.annotation.JsonProperty("eval_count") Integer evalCount) {}
    private record StreamChunk(Message message, boolean done,
                               @com.fasterxml.jackson.annotation.JsonProperty("prompt_eval_count") Integer promptEvalCount,
                               @com.fasterxml.jackson.annotation.JsonProperty("eval_count") Integer evalCount) {}
    private record Message(String role, String content, String thinking) {}
}
