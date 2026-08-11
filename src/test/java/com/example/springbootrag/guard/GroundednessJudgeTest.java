package com.example.springbootrag.guard;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.config.ChatProperties;
import com.example.springbootrag.config.GuardProperties;
import com.example.springbootrag.model.SearchHit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

class GroundednessJudgeTest {

    private static SearchHit hit(int index, String content) {
        return new SearchHit(index, "policy", index, content, "policy.md", null, 1.0, null);
    }

    /** Stub whose reply is a function of (systemPrompt, userPrompt); records the options it saw. */
    private static class StubChat implements ChatProvider {
        final AtomicReference<String> lastUserPrompt = new AtomicReference<>();
        final AtomicReference<Options> lastOptions = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();
        BiFunction<String, String, String> reply = (s, u) -> "{\"supported\":true}";

        @Override public String chat(String systemPrompt, String userPrompt) {
            calls.incrementAndGet();
            lastUserPrompt.set(userPrompt);
            return reply.apply(systemPrompt, userPrompt);
        }

        @Override public String chat(String systemPrompt, String userPrompt, Options options) {
            lastOptions.set(options);
            return chat(systemPrompt, userPrompt);
        }
    }

    private static GuardProperties props(boolean enabled) {
        GuardProperties p = new GuardProperties();
        p.getGroundedness().setEnabled(enabled);
        return p;
    }

    private static GroundednessJudge judge(StubChat chat, boolean enabled) {
        return new GroundednessJudge(chat, props(enabled), new ChatProperties());
    }

    @Test
    void aSupportedAnswerPasses() {
        StubChat chat = new StubChat();
        chat.reply = (s, u) -> "{\"supported\":true,\"unsupported_claim\":null}";

        GroundednessJudge.Result r = judge(chat, true)
                .judge("Meals are 40 EUR [1].", List.of(hit(1, "The meal allowance is 40 EUR.")));

        assertThat(r.supported()).isTrue();
    }

    @Test
    void anUnsupportedAnswerFails() {
        StubChat chat = new StubChat();
        chat.reply = (s, u) -> "{\"supported\":false,\"unsupported_claim\":\"60 EUR\"}";

        GroundednessJudge.Result r = judge(chat, true)
                .judge("Meals are 60 EUR [1].", List.of(hit(1, "The meal allowance is 40 EUR.")));

        assertThat(r.supported()).isFalse();
        assertThat(r.unsupportedClaim()).isEqualTo("60 EUR");
    }

    @Test
    void onlyTheCitedChunksAreSentToTheJudge() {
        // Sending all of them would pay for context the check does not need, and would let a chunk
        // the answer never sourced support one of its claims.
        StubChat chat = new StubChat();

        judge(chat, true).judge("Only the second one [2].",
                List.of(hit(1, "FIRST CHUNK"), hit(2, "SECOND CHUNK")));

        assertThat(chat.lastUserPrompt.get()).contains("SECOND CHUNK");
        assertThat(chat.lastUserPrompt.get()).doesNotContain("FIRST CHUNK");
    }

    @Test
    void aBrokenJudgeAllows() {
        // A judge outage must not turn into a system that refuses every answer.
        StubChat thrown = new StubChat();
        thrown.reply = (s, u) -> { throw new IllegalStateException("model down"); };
        StubChat garbage = new StubChat();
        garbage.reply = (s, u) -> "not json at all";

        assertThat(judge(thrown, true).judge("A [1].", List.of(hit(1, "a"))).supported()).isTrue();
        assertThat(judge(garbage, true).judge("A [1].", List.of(hit(1, "a"))).supported()).isTrue();
    }

    @Test
    void theCallIsPinnedForDeterminism() {
        StubChat chat = new StubChat();

        judge(chat, true).judge("A [1].", List.of(hit(1, "a")));

        ChatProvider.Options o = chat.lastOptions.get();
        assertThat(o.temperature()).isEqualTo(0.0);
        assertThat(o.seed()).isEqualTo(42);
        assertThat(o.think()).isFalse();
        assertThat(o.responseSchema()).isNotNull();
    }

    @Test
    void disabledMeansNoCallAtAll() {
        // The default. An ordinary answer must cost exactly the calls it cost before this class.
        StubChat chat = new StubChat();

        GroundednessJudge.Result r = judge(chat, false).judge("A [1].", List.of(hit(1, "a")));

        assertThat(chat.calls.get()).isZero();
        assertThat(r.supported()).isTrue();
        assertThat(r.latencyMs()).isZero();
    }

    @Test
    void theDefaultIsOff() {
        assertThat(new GuardProperties().getGroundedness().isEnabled()).isFalse();
    }
}
