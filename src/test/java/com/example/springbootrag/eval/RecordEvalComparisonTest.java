package com.example.springbootrag.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Every gate rule, verified offline. The eval itself takes half an hour; these take milliseconds. */
class RecordEvalComparisonTest {

    private static final double TOLERANCE = 0.05;

    private static RecordEvalBaseline baseline(double conditionRecall, int noFilterCorrect,
                                               double recall5With, List<String> filtered) {
        return new RecordEvalBaseline(42, 210,
                List.of("q1", "q2", "q3"),
                new RecordEvalBaseline.Extraction(0.79, conditionRecall, 1.00, noFilterCorrect),
                Map.of("with-extraction", new BackendMetrics(recall5With, 1.00, 1.00),
                        "without-extraction", new BackendMetrics(0.64, 0.59, 0.55)),
                filtered);
    }

    private static RecordEvalBaseline reference() {
        return baseline(0.80, 2, 1.00, List.of("q1", "q2"));
    }

    @Test
    void anIdenticalRunPasses() {
        assertThat(RecordEvalComparison.compare(reference(), reference(), TOLERANCE)).isEmpty();
    }

    @Test
    void improvementNeverFails() {
        // A floor, not a pin.
        RecordEvalBaseline better = baseline(0.95, 2, 1.00, List.of("q1", "q2", "q3"));

        assertThat(RecordEvalComparison.compare(reference(), better, TOLERANCE)).isEmpty();
    }

    @Test
    void aSmallDropInsideToleranceIsAccepted() {
        // Sampling moves these numbers run to run; the gate must not cry wolf.
        RecordEvalBaseline noisy = baseline(0.77, 2, 1.00, List.of("q1", "q2"));

        assertThat(RecordEvalComparison.compare(reference(), noisy, TOLERANCE)).isEmpty();
    }

    @Test
    void aRealConditionRecallDropFails() {
        // The prompt-layout bug took condition recall from 0.73 to 0.07. This is that signal.
        RecordEvalBaseline broken = baseline(0.07, 2, 1.00, List.of("q1", "q2"));

        assertThat(RecordEvalComparison.compare(reference(), broken, TOLERANCE))
                .anyMatch(v -> v.area().equals("extraction") && v.detail().contains("condition recall"));
    }

    @Test
    void losingAFilterOnOneQuestionFailsEvenWhenAggregatesHold() {
        RecordEvalBaseline lost = baseline(0.80, 2, 1.00, List.of("q1"));

        assertThat(RecordEvalComparison.compare(reference(), lost, TOLERANCE))
                .anyMatch(v -> v.detail().contains("no filter extracted any more for: q2"));
    }

    @Test
    void newOverExtractionFailsWithNoTolerance() {
        // Over-extraction hides findable documents, so one lost no-filter question is a failure.
        RecordEvalBaseline overEager = baseline(0.80, 1, 1.00, List.of("q1", "q2"));

        assertThat(RecordEvalComparison.compare(reference(), overEager, TOLERANCE))
                .anyMatch(v -> v.detail().contains("must produce no filter"));
    }

    @Test
    void aRetrievalRegressionFails() {
        RecordEvalBaseline worse = baseline(0.80, 2, 0.50, List.of("q1", "q2"));

        assertThat(RecordEvalComparison.compare(reference(), worse, TOLERANCE))
                .anyMatch(v -> v.area().equals("with-extraction") && v.detail().contains("recall@5"));
    }

    @Test
    void aRegeneratedCorpusReportsOneCauseNotTwelve() {
        RecordEvalBaseline other = new RecordEvalBaseline(99, 210, List.of("q1"),
                new RecordEvalBaseline.Extraction(0.0, 0.0, 0.0, 0),
                Map.of("with-extraction", new BackendMetrics(0, 0, 0)), List.of());

        List<RecordEvalComparison.Violation> violations =
                RecordEvalComparison.compare(reference(), other, TOLERANCE);

        assertThat(violations).singleElement()
                .satisfies(v -> assertThat(v.area()).isEqualTo("corpus"));
        assertThat(violations.getFirst().detail()).contains("baseline is stale");
    }

    @Test
    void aQuestionAddedSinceTheBaselineIsANoticeNotAFailure() {
        RecordEvalBaseline grown = new RecordEvalBaseline(42, 210,
                List.of("q1", "q2", "q3", "q4"), reference().extraction(),
                reference().retrieval(), List.of("q1", "q2"));

        assertThat(RecordEvalComparison.compare(reference(), grown, TOLERANCE)).isEmpty();
        assertThat(RecordEvalComparison.newQuestions(reference(), grown)).containsExactly("q4");
    }

    @Test
    void aQuestionRemovedFromTheGoldenSetDoesNotCountAsALostFilter() {
        // Deleting a question is an edit to the golden set, not a regression in extraction.
        RecordEvalBaseline shrunk = new RecordEvalBaseline(42, 210, List.of("q1"),
                reference().extraction(), reference().retrieval(), List.of("q1"));

        assertThat(RecordEvalComparison.compare(reference(), shrunk, TOLERANCE)).isEmpty();
    }

    @Test
    void aMissingRetrievalKeyIsReported() {
        RecordEvalBaseline partial = new RecordEvalBaseline(42, 210, List.of("q1", "q2", "q3"),
                reference().extraction(),
                Map.of("with-extraction", new BackendMetrics(1.00, 1.00, 1.00)),
                List.of("q1", "q2"));

        assertThat(RecordEvalComparison.compare(reference(), partial, TOLERANCE))
                .anyMatch(v -> v.area().equals("without-extraction"));
    }

    @Test
    void theStoreRoundTripsEveryField() {
        RecordEvalBaseline written = reference();

        RecordEvalBaseline read = RecordEvalBaselineStore.parse(
                RecordEvalBaselineStore.toMap(written));

        assertThat(read.corpusSeed()).isEqualTo(42);
        assertThat(read.corpusSize()).isEqualTo(210);
        assertThat(read.questions()).containsExactly("q1", "q2", "q3");
        assertThat(read.extraction().conditionRecall()).isEqualTo(0.80);
        assertThat(read.extraction().noFilterCorrect()).isEqualTo(2);
        assertThat(read.retrieval()).containsKeys("with-extraction", "without-extraction");
        assertThat(read.retrieval().get("without-extraction").recall5()).isEqualTo(0.64);
        assertThat(read.filtered()).containsExactly("q1", "q2");
    }
}
