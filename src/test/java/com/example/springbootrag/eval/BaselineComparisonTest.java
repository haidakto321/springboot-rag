package com.example.springbootrag.eval;

import com.example.springbootrag.eval.BaselineComparison.Violation;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BaselineComparisonTest {

    private static final double TOLERANCE = 0.02;

    private static final CorpusFingerprint CORPUS =
            new CorpusFingerprint(5L, "docmaster", 428, 7536);
    private static final CorpusFingerprint REIMPORTED =
            new CorpusFingerprint(5L, "docmaster", 430, 7602);

    private static final String Q1 = "Which two electronic-invoice formats are used for Germany?";
    private static final String Q2 = "From when is e-invoicing mandatory in Germany?";
    private static final String Q3 = "Which German forum is referenced for e-invoicing standards?";

    /** Baseline with a single backend, so each test varies exactly one thing. */
    private static EvalBaseline baseline(CorpusFingerprint corpus, List<String> questions,
                                         BackendMetrics metrics, List<String> found) {
        Map<String, BackendMetrics> m = new LinkedHashMap<>();
        m.put("hybrid", metrics);
        Map<String, List<String>> f = new LinkedHashMap<>();
        f.put("hybrid", found);
        return new EvalBaseline(corpus, "identity", questions, m, f);
    }

    private static EvalBaseline standard(BackendMetrics metrics, List<String> found) {
        return baseline(CORPUS, List.of(Q1, Q2), metrics, found);
    }

    @Test
    void passesWhenActualEqualsBaseline() {
        BackendMetrics m = new BackendMetrics(0.909, 0.919, 0.909);

        List<Violation> violations = BaselineComparison.compare(
                standard(m, List.of(Q1, Q2)), standard(m, List.of(Q1, Q2)), TOLERANCE);

        assertThat(violations).isEmpty();
    }

    @Test
    void passesWhenActualIsBetterThanBaseline() {
        EvalBaseline expected = standard(new BackendMetrics(0.909, 0.919, 0.909), List.of(Q1));
        EvalBaseline actual = standard(new BackendMetrics(1.0, 1.0, 1.0), List.of(Q1, Q2));

        assertThat(BaselineComparison.compare(expected, actual, TOLERANCE)).isEmpty();
    }

    @Test
    void passesWhenBelowBaselineButWithinTolerance() {
        EvalBaseline expected = standard(new BackendMetrics(0.909, 0.919, 0.909), List.of(Q1, Q2));
        EvalBaseline actual = standard(new BackendMetrics(0.909, 0.909, 0.909), List.of(Q1, Q2));

        assertThat(BaselineComparison.compare(expected, actual, TOLERANCE)).isEmpty();
    }

    @Test
    void failsWhenBelowBaselineBeyondTolerance() {
        EvalBaseline expected = standard(new BackendMetrics(0.909, 0.919, 0.909), List.of(Q1, Q2));
        EvalBaseline actual = standard(new BackendMetrics(0.909, 0.827, 0.909), List.of(Q1, Q2));

        List<Violation> violations = BaselineComparison.compare(expected, actual, TOLERANCE);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).backend()).isEqualTo("hybrid");
        assertThat(violations.get(0).detail()).contains("MRR").contains("0.827").contains("0.899");
    }

    /**
     * The 2026-08-05 regression, encoded. The cross-encoder pushed one question from rank 9 out of
     * the top 10: recall@5 and hit@1 did not move at all because that question was never inside
     * either window, and MRR moved only 0.010, well inside tolerance. Aggregate checks alone pass.
     */
    @Test
    void failsOnANewMissEvenWhenEveryAggregateMetricIsWithinTolerance() {
        EvalBaseline expected = standard(new BackendMetrics(0.909, 0.919, 0.909), List.of(Q1, Q2));
        EvalBaseline actual = standard(new BackendMetrics(0.909, 0.909, 0.909), List.of(Q1));

        List<Violation> violations = BaselineComparison.compare(expected, actual, TOLERANCE);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).detail()).contains("new miss").contains(Q2);
    }

    @Test
    void reportsAQuestionAbsentFromTheBaselineAsNewWithoutFailing() {
        EvalBaseline expected = baseline(CORPUS, List.of(Q1, Q2),
                new BackendMetrics(0.909, 0.919, 0.909), List.of(Q1, Q2));
        EvalBaseline actual = baseline(CORPUS, List.of(Q1, Q2, Q3),
                new BackendMetrics(0.909, 0.919, 0.909), List.of(Q1, Q2, Q3));

        assertThat(BaselineComparison.compare(expected, actual, TOLERANCE)).isEmpty();
        assertThat(BaselineComparison.newQuestions(expected, actual)).containsExactly(Q3);
    }

    @Test
    void failsOnABackendPresentInTheRunButAbsentFromTheBaseline() {
        EvalBaseline expected = standard(new BackendMetrics(0.909, 0.919, 0.909), List.of(Q1, Q2));

        Map<String, BackendMetrics> m = new LinkedHashMap<>();
        m.put("hybrid", new BackendMetrics(0.909, 0.919, 0.909));
        m.put("colbert", new BackendMetrics(0.909, 0.919, 0.909));
        Map<String, List<String>> f = new LinkedHashMap<>();
        f.put("hybrid", List.of(Q1, Q2));
        f.put("colbert", List.of(Q1, Q2));
        EvalBaseline actual = new EvalBaseline(CORPUS, "identity", List.of(Q1, Q2), m, f);

        List<Violation> violations = BaselineComparison.compare(expected, actual, TOLERANCE);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).backend()).isEqualTo("colbert");
        assertThat(violations.get(0).detail()).contains("absent from the baseline");
    }

    @Test
    void failsOnABackendPresentInTheBaselineButAbsentFromTheRun() {
        Map<String, BackendMetrics> m = new LinkedHashMap<>();
        m.put("hybrid", new BackendMetrics(0.909, 0.919, 0.909));
        m.put("graph", new BackendMetrics(0.909, 0.919, 0.909));
        Map<String, List<String>> f = new LinkedHashMap<>();
        f.put("hybrid", List.of(Q1, Q2));
        f.put("graph", List.of(Q1, Q2));
        EvalBaseline expected = new EvalBaseline(CORPUS, "identity", List.of(Q1, Q2), m, f);

        EvalBaseline actual = standard(new BackendMetrics(0.909, 0.919, 0.909), List.of(Q1, Q2));

        List<Violation> violations = BaselineComparison.compare(expected, actual, TOLERANCE);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).backend()).isEqualTo("graph");
        assertThat(violations.get(0).detail()).contains("was not run");
    }

    /** A stale baseline reports one clear cause, never a pile of fake backend regressions. */
    @Test
    void reportsOnlyTheCorpusMismatchEvenWhenMetricsAlsoRegressed() {
        EvalBaseline expected = baseline(CORPUS, List.of(Q1, Q2),
                new BackendMetrics(0.909, 0.919, 0.909), List.of(Q1, Q2));
        EvalBaseline actual = baseline(REIMPORTED, List.of(Q1, Q2),
                new BackendMetrics(0.100, 0.100, 0.100), List.of());

        List<Violation> violations = BaselineComparison.compare(expected, actual, TOLERANCE);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).backend()).isEqualTo("corpus");
        assertThat(violations.get(0).detail())
                .contains("7536")
                .contains("7602")
                .contains("-Deval.baseline.update=true");
    }
}
