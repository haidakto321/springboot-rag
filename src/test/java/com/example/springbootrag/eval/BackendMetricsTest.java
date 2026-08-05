package com.example.springbootrag.eval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BackendMetricsTest {

    /** The measured 2026-08-05 pgvector row: ten questions at rank 1, one at rank 9. */
    @Test
    void computesTheMeasuredPgvectorRow() {
        int[] ranks = {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 9};

        BackendMetrics m = BackendMetrics.of(ranks, 11);

        assertThat(m.recall5()).isCloseTo(0.909, within(0.001));
        assertThat(m.mrr()).isCloseTo(0.919, within(0.001));
        assertThat(m.hit1()).isCloseTo(0.909, within(0.001));
    }

    /** The measured 2026-08-05 fts row: two questions at rank 1, nine missed. */
    @Test
    void computesTheMeasuredFtsRow() {
        int[] ranks = {0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0};

        BackendMetrics m = BackendMetrics.of(ranks, 11);

        assertThat(m.recall5()).isCloseTo(0.182, within(0.001));
        assertThat(m.mrr()).isCloseTo(0.182, within(0.001));
        assertThat(m.hit1()).isCloseTo(0.182, within(0.001));
    }

    /** recall@5 counts ranks 1 to 5 only; rank 6 is inside topK but outside the window. */
    @Test
    void recallAtFiveExcludesRanksBeyondFive() {
        BackendMetrics m = BackendMetrics.of(new int[]{5, 6}, 2);

        assertThat(m.recall5()).isCloseTo(0.5, within(0.001));
        assertThat(m.hit1()).isCloseTo(0.0, within(0.001));
    }

    /** A miss (rank 0) contributes nothing to any metric and must not divide by zero. */
    @Test
    void missesContributeNothing() {
        BackendMetrics m = BackendMetrics.of(new int[]{0, 0, 0}, 3);

        assertThat(m.recall5()).isEqualTo(0.0);
        assertThat(m.mrr()).isEqualTo(0.0);
        assertThat(m.hit1()).isEqualTo(0.0);
    }
}
