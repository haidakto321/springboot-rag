package com.example.springbootrag.guard;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.config.ChatProperties;
import com.example.springbootrag.config.GuardProperties;
import com.example.springbootrag.model.SearchHit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks that a cited answer says what the chunk it cites actually says.
 *
 * <p>{@link AnswerGuard} proves a citation EXISTS and is in range. It cannot see the failure the
 * 2026-08-05 drill named last: an answer that keeps citing while misstating its source passes it
 * untouched.
 *
 * <p>Built on what {@link com.example.springbootrag.understand.QueryRouter} measured on this box -
 * a short prompt plus a response schema, temperature 0, a fixed seed, thinking off. The schema is
 * what makes a small reasoning model answer at all rather than spend its whole budget restating
 * the question.
 *
 * <p>Ships DISABLED. Refusing a good answer is a worse product failure than the leak this closes,
 * because it happens on every ordinary question rather than on an attack, and the false-refusal
 * rate has not been measured yet. Any failure means ALLOW: a judge outage must not become a system
 * that refuses everything.
 */
@Service
public class GroundednessJudge {

    private static final Logger log = LoggerFactory.getLogger(GroundednessJudge.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "supported", Map.of("type", "boolean"),
                    "unsupported_claim", Map.of("type", List.of("string", "null"))),
            "required", List.of("supported"));

    static final String SYSTEM = """
            You check whether an answer is supported by the material it cites. Reply with JSON only.

            supported = true when every factual claim in the answer appears in the material.
            supported = false when the answer states something the material does not say, or
            contradicts it. Wording may differ; only the facts must match.

            A refusal, or an answer that only says the material does not cover the question, is
            supported = true.""";

    /** @param latencyMs wall time of the judge call, 0 when the judge did not run */
    public record Result(boolean supported, String unsupportedClaim, long latencyMs) {
        public static Result allow() {
            return new Result(true, null, 0L);
        }
    }

    private final ChatProvider chat;
    private final GuardProperties props;
    private final ChatProperties chatProps;

    public GroundednessJudge(ChatProvider chat, GuardProperties props, ChatProperties chatProps) {
        this.chat = chat;
        this.props = props;
        this.chatProps = chatProps;
    }

    public boolean enabled() {
        return props.getGroundedness().isEnabled();
    }

    /** Never throws. Anything unexpected is an allow. */
    public Result judge(String answer, List<SearchHit> hits) {
        if (!enabled() || answer == null || answer.isBlank() || hits == null || hits.isEmpty()) {
            return Result.allow();
        }
        long start = System.nanoTime();
        try {
            String reply = chat.chat(SYSTEM, buildPrompt(answer, hits),
                    new ChatProvider.Options(model(), 0.0, props.getGroundedness().getSeed(),
                            false, null, SCHEMA));
            JsonNode node = MAPPER.readTree(reply);
            if (!node.hasNonNull("supported")) {
                log.warn("groundedness judge returned no verdict, allowing: {}", reply);
                return Result.allow();
            }
            String claim = node.hasNonNull("unsupported_claim")
                    ? node.get("unsupported_claim").asText() : null;
            return new Result(node.get("supported").asBoolean(), claim, msSince(start));
        } catch (Exception e) {
            log.warn("groundedness judge failed, allowing the answer", e);
            return Result.allow();
        }
    }

    /** Which model judges - empty config means the answer model. */
    private String model() {
        String m = props.getGroundedness().getModel();
        return m == null || m.isBlank() ? chatProps.getModel() : m;
    }

    /**
     * Only the chunks the answer actually cited. Sending all ten would pay for context the check
     * does not need, and would let a chunk the answer never sourced support one of its claims.
     */
    static String buildPrompt(String answer, List<SearchHit> hits) {
        Set<Integer> cited = AnswerGuard.citations(answer);
        StringBuilder sb = new StringBuilder("Material:\n");
        for (int n : cited) {
            if (n >= 1 && n <= hits.size()) {
                sb.append('[').append(n).append("] ").append(hits.get(n - 1).content()).append('\n');
            }
        }
        return sb.append("\nAnswer:\n").append(answer).toString();
    }

    private static long msSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
