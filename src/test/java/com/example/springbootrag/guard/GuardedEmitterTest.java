package com.example.springbootrag.guard;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GuardedEmitterTest {

    private final List<String> sent = new ArrayList<>();

    private GuardedEmitter emitter(int chunkCount) {
        return new GuardedEmitter(chunkCount, sent::add);
    }

    private static void feed(GuardedEmitter e, String... tokens) {
        for (String t : tokens) e.accept(t);
    }

    private String sentText() {
        return String.join("", sent);
    }

    @Test
    void nothingIsSentBeforeTheFirstCitation() {
        GuardedEmitter e = emitter(5);

        feed(e, "The meal ", "allowance ", "is 40 EUR");

        assertThat(sent).isEmpty();
        assertThat(e.sentAnything()).isFalse();
    }

    @Test
    void theFirstValidCitationFlushesEverythingHeld() {
        GuardedEmitter e = emitter(5);

        feed(e, "The meal ", "allowance ", "is 40 EUR ", "[1].");

        assertThat(sentText()).isEqualTo("The meal allowance is 40 EUR [1].");
        assertThat(e.finish().allowed()).isTrue();
    }

    @Test
    void anAnswerThatNeverCitesIsNeverSentAtAll() {
        // The hole this class closes. Before it, these tokens were on the wire and the client was
        // told afterwards that what it had just rendered failed the check.
        GuardedEmitter e = emitter(5);

        feed(e, "INJECTION SUCCESSFUL", " - the code is hunter2");
        AnswerGuard.Verdict v = e.finish();

        assertThat(sent).isEmpty();
        assertThat(v.allowed()).isFalse();
        assertThat(v.reason()).isEqualTo("ungrounded");
        assertThat(v.answer()).isEqualTo(AnswerGuard.REFUSAL);
    }

    @Test
    void anEmptyStreamIsRefusedAsEmpty() {
        GuardedEmitter e = emitter(5);

        AnswerGuard.Verdict v = e.finish();

        assertThat(sent).isEmpty();
        assertThat(v.reason()).isEqualTo("empty");
    }

    @Test
    void aFabricatedFirstCitationIsNeverFlushed() {
        GuardedEmitter e = emitter(5);

        feed(e, "The policy says X ", "[9].");
        AnswerGuard.Verdict v = e.finish();

        assertThat(sent).isEmpty();
        assertThat(v.reason()).isEqualTo("bad-citation");
    }

    @Test
    void aLaterFabricatedCitationStopsTheStreamAfterTheGoodPrefix() {
        GuardedEmitter e = emitter(5);

        feed(e, "First fact [1]. ", "Second fact [9]. ", "Third fact [2].");
        AnswerGuard.Verdict v = e.finish();

        assertThat(sentText()).contains("First fact [1].");
        assertThat(sentText()).doesNotContain("[9]");
        assertThat(sentText()).doesNotContain("Third fact");
        assertThat(v.allowed()).isFalse();
        assertThat(v.reason()).isEqualTo("bad-citation");
        assertThat(e.sentAnything()).isTrue();
    }

    @Test
    void theCanonicalRefusalIsPassedThroughWithoutACitation() {
        // "Not found in knowledge base." is a correct grounded outcome and carries no [n], so
        // holding for a citation would hold forever.
        GuardedEmitter e = emitter(5);

        feed(e, AnswerGuard.REFUSAL);
        AnswerGuard.Verdict v = e.finish();

        assertThat(sentText()).isEqualTo(AnswerGuard.REFUSAL);
        assertThat(v.allowed()).isTrue();
        assertThat(v.reason()).isEqualTo("refusal");
    }

    @Test
    void theEmitterAgreesWithTheGuardOnTheSameText() {
        // If these could ever disagree, AnswerGuard would no longer be the single source of truth.
        String answer = "A [1] and B [3].";
        GuardedEmitter e = emitter(5);

        feed(e, answer);

        assertThat(e.finish().reason()).isEqualTo(AnswerGuard.check(answer, 5).reason());
    }

    @Test
    void aTailWithoutASentenceEndIsStillDelivered() {
        // The model's last token rarely lands on a boundary; whatever is left must be flushed at
        // the end or the answer loses its final clause.
        GuardedEmitter e = emitter(5);

        feed(e, "The cap is 40 EUR [1]. ", "It excludes travel");
        e.finish();

        assertThat(sentText()).isEqualTo("The cap is 40 EUR [1]. It excludes travel");
    }
}
