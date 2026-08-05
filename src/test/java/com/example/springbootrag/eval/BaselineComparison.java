package com.example.springbootrag.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Compares a measured eval run against a committed baseline. Pure: no Spring, no I/O, no database,
 * so every gate rule is unit-tested offline in milliseconds.
 *
 * <p>The gate is a floor, not a pin. Improvement never fails: a metric above baseline passes, and a
 * question that was missed before but is found now passes.
 */
public final class BaselineComparison {

    /** One reason the gate failed. {@code backend} is "corpus" for a stale-baseline violation. */
    public record Violation(String backend, String detail) {}

    private BaselineComparison() {}

    public static List<Violation> compare(EvalBaseline expected, EvalBaseline actual, double tolerance) {
        // A re-imported corpus moves every number for reasons that are not regressions. Report that
        // one cause and stop, rather than six backend failures that read like a real defect.
        if (!expected.corpus().equals(actual.corpus())) {
            return List.of(new Violation("corpus", String.format(Locale.ROOT,
                    "corpus changed: %s -> %s; baseline is stale, regenerate with "
                            + "-Deval.baseline.update=true",
                    describe(expected.corpus()), describe(actual.corpus()))));
        }

        List<Violation> violations = new ArrayList<>();

        for (String backend : expected.metrics().keySet()) {
            if (!actual.metrics().containsKey(backend)) {
                violations.add(new Violation(backend,
                        "backend is in the baseline but was not run - retrieval coverage was lost"));
            }
        }

        for (String backend : actual.metrics().keySet()) {
            BackendMetrics want = expected.metrics().get(backend);
            if (want == null) {
                violations.add(new Violation(backend,
                        "backend was run but is absent from the baseline - regenerate with "
                                + "-Deval.baseline.update=true"));
                continue;
            }
            BackendMetrics got = actual.metrics().get(backend);
            checkFloor(violations, backend, "recall@5", want.recall5(), got.recall5(), tolerance);
            checkFloor(violations, backend, "MRR", want.mrr(), got.mrr(), tolerance);
            checkFloor(violations, backend, "hit@1", want.hit1(), got.hit1(), tolerance);

            List<String> nowFound = actual.found().getOrDefault(backend, List.of());
            for (String question : expected.found().getOrDefault(backend, List.of())) {
                if (!nowFound.contains(question)) {
                    violations.add(new Violation(backend,
                            "new miss: the baseline found this question, this run did not: " + question));
                }
            }
        }
        return violations;
    }

    /** Questions in this run that the baseline has never seen. A notice, never a failure. */
    public static List<String> newQuestions(EvalBaseline expected, EvalBaseline actual) {
        return actual.questions().stream()
                .filter(q -> !expected.questions().contains(q))
                .toList();
    }

    private static void checkFloor(List<Violation> out, String backend, String metric,
                                   double expected, double actual, double tolerance) {
        double floor = expected - tolerance;
        if (actual < floor) {
            out.add(new Violation(backend, String.format(Locale.ROOT,
                    "%s %.3f is below the floor %.3f (baseline %.3f minus tolerance %.3f)",
                    metric, actual, floor, expected, tolerance)));
        }
    }

    private static String describe(CorpusFingerprint fp) {
        return String.format(Locale.ROOT, "project %d '%s' with %d docs / %d chunks",
                fp.projectId(), fp.projectName(), fp.docCount(), fp.chunkCount());
    }
}
