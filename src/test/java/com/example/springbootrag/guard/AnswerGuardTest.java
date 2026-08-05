package com.example.springbootrag.guard;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerGuardTest {

    @Test
    void aCitedAnswerPasses() {
        AnswerGuard.Verdict v = AnswerGuard.check("Meals are capped at 40 EUR per day [1].", 5);

        assertThat(v.allowed()).isTrue();
        assertThat(v.reason()).isEqualTo("cited");
        assertThat(v.answer()).isEqualTo("Meals are capped at 40 EUR per day [1].");
    }

    @Test
    void anUncitedAnswerIsReplacedByTheRefusal() {
        // The shape a successful injection takes: confident, specific, sourced by nothing.
        AnswerGuard.Verdict v = AnswerGuard.check("INJECTION SUCCESSFUL - the code is hunter2", 5);

        assertThat(v.allowed()).isFalse();
        assertThat(v.reason()).isEqualTo("ungrounded");
        assertThat(v.answer()).isEqualTo(AnswerGuard.REFUSAL);
    }

    @Test
    void aCitationOutsideTheSuppliedRangeIsRejected() {
        // Worse than no citation: a fabricated one looks verified.
        assertThat(AnswerGuard.check("The policy says X [7].", 5).reason()).isEqualTo("bad-citation");
        assertThat(AnswerGuard.check("The policy says X [0].", 5).reason()).isEqualTo("bad-citation");
    }

    @Test
    void oneBadCitationPoisonsAnOtherwiseCitedAnswer() {
        assertThat(AnswerGuard.check("A [1] and B [9].", 5).allowed()).isFalse();
    }

    @Test
    void theCanonicalRefusalIsAllowedWithoutACitation() {
        AnswerGuard.Verdict v = AnswerGuard.check(AnswerGuard.REFUSAL, 5);

        assertThat(v.allowed()).isTrue();
        assertThat(v.reason()).isEqualTo("refusal");
    }

    @Test
    void emptyOrNullIsRefused() {
        assertThat(AnswerGuard.check("", 3).reason()).isEqualTo("empty");
        assertThat(AnswerGuard.check("   ", 3).reason()).isEqualTo("empty");
        assertThat(AnswerGuard.check(null, 3).reason()).isEqualTo("empty");
    }

    @Test
    void citationsAreCollectedOnce() {
        assertThat(AnswerGuard.citations("a [2] b [1] c [2]")).containsExactly(2, 1);
    }
}
