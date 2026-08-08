package com.example.springbootrag.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Compares a measured record-eval run against its committed baseline. Pure: no Spring, no model, no
 * database, so every gate rule is unit-tested offline in milliseconds instead of in half an hour.
 *
 * <p>A floor, not a pin, exactly like {@link BaselineComparison}: improvement never fails the gate.
 * Extraction quality drifts with model version and sampling, so the tolerance is real and the
 * per-question checks catch the losses an aggregate hides.
 */
public final class RecordEvalComparison {

    /** One reason the gate failed. {@code area} is "corpus", "extraction", or a retrieval key. */
    public record Violation(String area, String detail) {}

    private RecordEvalComparison() {}

    public static List<Violation> compare(RecordEvalBaseline expected, RecordEvalBaseline actual,
                                          double tolerance) {
        // A regenerated corpus moves every number for reasons that are not regressions. Report the
        // one cause and stop, rather than a dozen failures that read like a real defect.
        if (expected.corpusSeed() != actual.corpusSeed()
                || expected.corpusSize() != actual.corpusSize()) {
            return List.of(new Violation("corpus", String.format(Locale.ROOT,
                    "corpus changed: seed %d/%d records -> seed %d/%d; baseline is stale, "
                            + "regenerate with -Deval.baseline.update=true",
                    expected.corpusSeed(), expected.corpusSize(),
                    actual.corpusSeed(), actual.corpusSize())));
        }

        List<Violation> violations = new ArrayList<>();

        RecordEvalBaseline.Extraction want = expected.extraction();
        RecordEvalBaseline.Extraction got = actual.extraction();
        floor(violations, "extraction", "condition precision",
                want.conditionPrecision(), got.conditionPrecision(), tolerance);
        floor(violations, "extraction", "condition recall",
                want.conditionRecall(), got.conditionRecall(), tolerance);
        floor(violations, "extraction", "docType accuracy",
                want.docTypeAccuracy(), got.docTypeAccuracy(), tolerance);
        if (got.noFilterCorrect() < want.noFilterCorrect()) {
            // Over-extraction is the failure that hides findable documents, so it is gated as a
            // count with no tolerance at all.
            violations.add(new Violation("extraction", String.format(Locale.ROOT,
                    "a question that must produce no filter now produces one: %d correct, "
                            + "baseline had %d", got.noFilterCorrect(), want.noFilterCorrect())));
        }

        // Routing is gated with NO tolerance. A misroute is not a slightly worse answer, it is the
        // wrong SHAPE of answer: a counting question answered with prose, or a document question
        // answered with a number.
        if (actual.routing().routeAccuracy() < expected.routing().routeAccuracy()) {
            violations.add(new Violation("routing", String.format(Locale.ROOT,
                    "route accuracy %.3f is below the baseline %.3f",
                    actual.routing().routeAccuracy(), expected.routing().routeAccuracy())));
        }
        if (actual.routing().aggregateCountCorrect() < expected.routing().aggregateCountCorrect()) {
            violations.add(new Violation("routing", String.format(Locale.ROOT,
                    "aggregate count correct for %d questions, baseline had %d",
                    actual.routing().aggregateCountCorrect(),
                    expected.routing().aggregateCountCorrect())));
        }
        // Per question, because an aggregate question demoted to search still answers - just with
        // ten chunks and no number - and the aggregate accuracy alone can absorb one of those.
        for (int i = 0; i < expected.questions().size() && i < expected.routes().size(); i++) {
            String question = expected.questions().get(i);
            int at = actual.questions().indexOf(question);
            if (at < 0 || at >= actual.routes().size()) continue;
            String was = expected.routes().get(i);
            String now = actual.routes().get(at);
            if (!was.equals(now)) {
                violations.add(new Violation("routing", String.format(Locale.ROOT,
                        "route changed for \"%s\": %s -> %s", question, was, now)));
            }
        }

        for (String key : expected.retrieval().keySet()) {
            BackendMetrics wantMetrics = expected.retrieval().get(key);
            BackendMetrics gotMetrics = actual.retrieval().get(key);
            if (gotMetrics == null) {
                violations.add(new Violation(key, "measured in the baseline but not in this run"));
                continue;
            }
            floor(violations, key, "recall@5", wantMetrics.recall5(), gotMetrics.recall5(), tolerance);
            floor(violations, key, "MRR", wantMetrics.mrr(), gotMetrics.mrr(), tolerance);
            floor(violations, key, "hit@1", wantMetrics.hit1(), gotMetrics.hit1(), tolerance);
        }

        // A question that used to yield a filter and now yields none is a regression even when the
        // aggregates stay inside tolerance - it is exactly how the prompt-layout bug presented.
        for (String question : expected.filtered()) {
            if (!actual.filtered().contains(question) && actual.questions().contains(question)) {
                violations.add(new Violation("extraction",
                        "no filter extracted any more for: " + question));
            }
        }
        return violations;
    }

    /** Questions in this run the baseline has never seen. A notice, never a failure. */
    public static List<String> newQuestions(RecordEvalBaseline expected, RecordEvalBaseline actual) {
        return actual.questions().stream()
                .filter(q -> !expected.questions().contains(q))
                .toList();
    }

    private static void floor(List<Violation> out, String area, String metric,
                              double expected, double actual, double tolerance) {
        double floor = expected - tolerance;
        if (actual < floor) {
            out.add(new Violation(area, String.format(Locale.ROOT,
                    "%s %.3f is below the floor %.3f (baseline %.3f minus tolerance %.3f)",
                    metric, actual, floor, expected, tolerance)));
        }
    }
}
