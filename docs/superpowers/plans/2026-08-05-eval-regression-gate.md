# Retrieval Eval Regression Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn `WikiRetrievalEvalTest` from a report into a regression gate that fails when retrieval quality drops against a committed baseline.

**Architecture:** The test already sweeps 6 backends x 11 golden questions once and holds the result in `List<BackendRun>`. Four new small units sit beside it: `CorpusFingerprint` and `BackendMetrics` (data), `EvalBaseline` (the whole baseline as a record), `BaselineComparison` (a pure function producing violations), and `EvalBaselineStore` (YAML load and write). The test gains an assertion block after its three existing print blocks. The comparison logic is pure, so it is unit-tested offline with fabricated numbers - no corpus, no Docker, no Ollama.

**Tech Stack:** Java 21 target on Java 25, Spring Boot 3.5.6, JUnit 5, AssertJ, SnakeYAML (already on the test classpath transitively via Spring Boot - do NOT add a dependency), Maven Surefire tag exclusion.

**Spec:** `docs/superpowers/specs/2026-08-05-eval-regression-gate-design.md`

## Global Constraints

- **Never run `git add` or `git commit`.** The owner's standing rule. Commit steps below are written out so the owner can run them, and so a subagent CAN run them if the owner explicitly waives the rule for that run (as they did on 2026-07-28). Absent an explicit waiver, stop after the test step and report the staged work.
- If a commit is ever made, its message must NOT contain a `Co-Authored-By: Claude` trailer.
- **Never use the em-dash character.** Use `-` instead. Applies to code, comments, docs, and commit messages.
- **No new dependencies.** SnakeYAML is already available (`GoldenSet` uses `org.yaml.snakeyaml.Yaml`). Do not add it to `pom.xml`.
- Use `./mvnw`, never `mvn`.
- `WikiRetrievalEvalTest` stays **read-only by construction**: only `SearchService`, `ProjectRepository`, and `Reranker` may be injected. Never add `IngestService`. Keep `@TestPropertySource(properties = "spring.sql.init.mode=never")`.
- Tolerance is the single constant `0.02`, applied to all three metrics and all backends.
- The eval tag `eval-wiki` stays excluded by default (`pom.xml:21`). Do not change the exclusion list.
- New unit tests (`BackendMetricsTest`, `BaselineComparisonTest`, `EvalBaselineStoreTest`) must be **untagged**, so they run in the normal `./mvnw test` build and require no Docker, no Ollama, and no corpus.
- The gate must never fire on a fresh clone. `requireCorpus()` skips first; every gate check runs after it.

## File Structure

**Create:**
| File | Responsibility |
|---|---|
| `src/test/java/com/example/springbootrag/eval/CorpusFingerprint.java` | Record identifying which corpus a baseline was measured on |
| `src/test/java/com/example/springbootrag/eval/BackendMetrics.java` | Record of recall@5 / MRR / hit@1, and the one place they are computed |
| `src/test/java/com/example/springbootrag/eval/EvalBaseline.java` | Record holding a whole baseline: corpus, variant, questions, metrics, found sets |
| `src/test/java/com/example/springbootrag/eval/BaselineComparison.java` | Pure comparison: `(expected, actual, tolerance) -> List<Violation>` |
| `src/test/java/com/example/springbootrag/eval/EvalBaselineStore.java` | Load the baseline from the classpath, write one variant back to the source tree |
| `src/test/java/com/example/springbootrag/eval/BackendMetricsTest.java` | Unit test for metric arithmetic |
| `src/test/java/com/example/springbootrag/eval/BaselineComparisonTest.java` | Unit test for every gate rule |
| `src/test/java/com/example/springbootrag/eval/EvalBaselineStoreTest.java` | Round-trip and variant-preservation test |
| `src/test/resources/eval/baseline-wiki.yaml` | The committed baseline, generated in Task 4 |

**Modify:**
| File | Change |
|---|---|
| `src/test/java/com/example/springbootrag/eval/WikiRetrievalEvalTest.java` | `printAggregate` uses `BackendMetrics.of`; new gate block after the three print blocks |
| `README.md` | Document the gate and the regenerate flag |
| `docs/RAG-MASTERY.md` | Section 9 row 3: score 1 to 2, with the reasoning updated |
| `docs/implementation-notes.md` | New dated entry recording the design decisions |
| `docs/ROADMAP.md` | Mark the CI-gate limitation entry as still open, cross-referencing the plan |

---

### Task 1: `BackendMetrics` and `CorpusFingerprint`

Extracts the metric arithmetic that currently lives inline in `printAggregate` into one tested place, so the printed report and the baseline can never disagree.

**Files:**
- Create: `src/test/java/com/example/springbootrag/eval/BackendMetrics.java`
- Create: `src/test/java/com/example/springbootrag/eval/CorpusFingerprint.java`
- Create: `src/test/java/com/example/springbootrag/eval/BackendMetricsTest.java`
- Modify: `src/test/java/com/example/springbootrag/eval/WikiRetrievalEvalTest.java:133-145`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `record CorpusFingerprint(long projectId, String projectName, int docCount, int chunkCount)`
  - `record BackendMetrics(double recall5, double mrr, double hit1)` with `static BackendMetrics of(int[] ranks, int questionCount)`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/springbootrag/eval/BackendMetricsTest.java`:

```java
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=BackendMetricsTest -DfailIfNoTests=false`

Expected: compilation failure, `cannot find symbol: class BackendMetrics`.

- [ ] **Step 3: Create the two records**

Create `src/test/java/com/example/springbootrag/eval/CorpusFingerprint.java`:

```java
package com.example.springbootrag.eval;

/**
 * Identifies which corpus a baseline was measured against, so a re-import is reported as a stale
 * baseline rather than as six simultaneous backend regressions.
 *
 * <p>This is a staleness check, not an integrity check: a corpus edited in place that preserves
 * both counts is not detected.
 */
public record CorpusFingerprint(long projectId, String projectName, int docCount, int chunkCount) {}
```

Create `src/test/java/com/example/springbootrag/eval/BackendMetrics.java`:

```java
package com.example.springbootrag.eval;

/**
 * One backend's aggregate retrieval quality over a golden set.
 *
 * <p>The single place these three numbers are computed. The printed report and the regression
 * baseline both call {@link #of}, so they cannot drift apart.
 */
public record BackendMetrics(double recall5, double mrr, double hit1) {

    /**
     * @param ranks 1-based rank of the expected document per question, 0 when it was not found
     * @param questionCount the golden set size, used as the denominator for all three metrics
     */
    public static BackendMetrics of(int[] ranks, int questionCount) {
        double recall5 = 0, mrr = 0, hit1 = 0;
        for (int rank : ranks) {
            if (rank >= 1 && rank <= 5) recall5++;
            if (rank >= 1) mrr += 1.0 / rank;
            if (rank == 1) hit1++;
        }
        return new BackendMetrics(recall5 / questionCount, mrr / questionCount, hit1 / questionCount);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=BackendMetricsTest -DfailIfNoTests=false`

Expected: `Tests run: 4, Failures: 0, Errors: 0`.

- [ ] **Step 5: Refactor `printAggregate` to use `BackendMetrics.of`**

In `WikiRetrievalEvalTest.java`, replace the whole `printAggregate` method (currently lines 133-145) with:

```java
    /** recall@5 is counted inside the first 5 of the TOP_K fetched, matching RetrievalEvalTest. */
    private static void printAggregate(List<BackendRun> runs, int questionCount) {
        System.out.printf("%n%-10s %10s %10s %10s%n", "backend", "recall@5", "MRR", "hit@1");
        for (BackendRun run : runs) {
            BackendMetrics m = BackendMetrics.of(run.ranks(), questionCount);
            System.out.printf(Locale.ROOT, "%-10s %10.3f %10.3f %10.3f%n",
                    run.backend(), m.recall5(), m.mrr(), m.hit1());
        }
    }
```

This is a pure refactor: identical arithmetic, identical format string, identical output.

- [ ] **Step 6: Verify the whole build still compiles and passes**

Run: `./mvnw test`

Expected: `Tests run: 112, Failures: 0, Errors: 0, Skipped: 3` (108 before, plus the 4 new ones). `BUILD SUCCESS`.

- [ ] **Step 7: Commit (only with an explicit owner waiver, see Global Constraints)**

```bash
git add src/test/java/com/example/springbootrag/eval/BackendMetrics.java src/test/java/com/example/springbootrag/eval/CorpusFingerprint.java src/test/java/com/example/springbootrag/eval/BackendMetricsTest.java src/test/java/com/example/springbootrag/eval/WikiRetrievalEvalTest.java
git commit -m "test(eval): extract BackendMetrics and CorpusFingerprint records"
```

---

### Task 2: `EvalBaseline` and `BaselineComparison`

The gate's decision logic, as a pure function. This is the task that carries the real rules, and every one of them is tested offline.

**Files:**
- Create: `src/test/java/com/example/springbootrag/eval/EvalBaseline.java`
- Create: `src/test/java/com/example/springbootrag/eval/BaselineComparison.java`
- Create: `src/test/java/com/example/springbootrag/eval/BaselineComparisonTest.java`

**Interfaces:**
- Consumes: `CorpusFingerprint`, `BackendMetrics` from Task 1.
- Produces:
  - `record EvalBaseline(CorpusFingerprint corpus, String variant, List<String> questions, Map<String, BackendMetrics> metrics, Map<String, List<String>> found)`
  - `record BaselineComparison.Violation(String backend, String detail)`
  - `static List<Violation> BaselineComparison.compare(EvalBaseline expected, EvalBaseline actual, double tolerance)`
  - `static List<String> BaselineComparison.newQuestions(EvalBaseline expected, EvalBaseline actual)`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/springbootrag/eval/BaselineComparisonTest.java`:

```java
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=BaselineComparisonTest -DfailIfNoTests=false`

Expected: compilation failure, `cannot find symbol: class EvalBaseline`.

- [ ] **Step 3: Create `EvalBaseline`**

Create `src/test/java/com/example/springbootrag/eval/EvalBaseline.java`:

```java
package com.example.springbootrag.eval;

import java.util.List;
import java.util.Map;

/**
 * One measured or expected eval result set for a single reranker variant.
 *
 * @param corpus which corpus this was measured on, used to detect a stale baseline
 * @param variant reranker variant name: "identity" or "djl"
 * @param questions the whole golden set in file order. Recorded in full, not only the found ones,
 *                  so a newly added question can be told apart from one that was always missed.
 * @param metrics backend name to its aggregate metrics
 * @param found backend name to the questions whose expected document it found (rank > 0)
 */
public record EvalBaseline(
        CorpusFingerprint corpus,
        String variant,
        List<String> questions,
        Map<String, BackendMetrics> metrics,
        Map<String, List<String>> found) {}
```

- [ ] **Step 4: Create `BaselineComparison`**

Create `src/test/java/com/example/springbootrag/eval/BaselineComparison.java`:

```java
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
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw test -Dtest=BaselineComparisonTest -DfailIfNoTests=false`

Expected: `Tests run: 9, Failures: 0, Errors: 0`.

- [ ] **Step 6: Run the whole build**

Run: `./mvnw test`

Expected: `Tests run: 121, Failures: 0, Errors: 0, Skipped: 3`. `BUILD SUCCESS`.

- [ ] **Step 7: Commit (only with an explicit owner waiver)**

```bash
git add src/test/java/com/example/springbootrag/eval/EvalBaseline.java src/test/java/com/example/springbootrag/eval/BaselineComparison.java src/test/java/com/example/springbootrag/eval/BaselineComparisonTest.java
git commit -m "test(eval): add pure baseline comparison with floors and new-miss rule"
```

---

### Task 3: `EvalBaselineStore`

Reads the baseline from the test classpath, and writes one variant back into the **source tree**. Writing to the classpath copy under `target/` would be silently discarded on the next build, so the write path deliberately targets `src/test/resources`.

**Files:**
- Create: `src/test/java/com/example/springbootrag/eval/EvalBaselineStore.java`
- Create: `src/test/java/com/example/springbootrag/eval/EvalBaselineStoreTest.java`

**Interfaces:**
- Consumes: `EvalBaseline`, `BackendMetrics`, `CorpusFingerprint` from Tasks 1 and 2.
- Produces:
  - `static final Path EvalBaselineStore.SOURCE`
  - `static EvalBaseline EvalBaselineStore.load(String variant)`
  - `static void EvalBaselineStore.write(EvalBaseline baseline)`
  - Package-private seams used only by the test: `static EvalBaseline parse(Map<String, Object> root, String variant)` and `static Map<String, Object> toMap(EvalBaseline baseline, Map<String, Object> existing)`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/springbootrag/eval/EvalBaselineStoreTest.java`:

```java
package com.example.springbootrag.eval;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class EvalBaselineStoreTest {

    private static final CorpusFingerprint CORPUS =
            new CorpusFingerprint(5L, "docmaster", 428, 7536);
    private static final String Q1 = "Which two electronic-invoice formats are used for Germany?";

    private static EvalBaseline sample(String variant, double mrr) {
        Map<String, BackendMetrics> metrics = new LinkedHashMap<>();
        metrics.put("hybrid", new BackendMetrics(0.909, mrr, 0.909));
        Map<String, List<String>> found = new LinkedHashMap<>();
        found.put("hybrid", List.of(Q1));
        return new EvalBaseline(CORPUS, variant, List.of(Q1), metrics, found);
    }

    @Test
    void roundTripsThroughTheYamlShape() {
        EvalBaseline original = sample("identity", 0.919);

        Map<String, Object> root = EvalBaselineStore.toMap(original, new LinkedHashMap<>());
        EvalBaseline parsed = EvalBaselineStore.parse(root, "identity");

        assertThat(parsed.corpus()).isEqualTo(CORPUS);
        assertThat(parsed.variant()).isEqualTo("identity");
        assertThat(parsed.questions()).containsExactly(Q1);
        assertThat(parsed.found()).containsEntry("hybrid", List.of(Q1));
        assertThat(parsed.metrics().get("hybrid").mrr()).isCloseTo(0.919, within(0.001));
    }

    /** Writing one variant must leave the other variant's numbers untouched. */
    @Test
    void writingOneVariantPreservesTheOther() {
        Map<String, Object> root = EvalBaselineStore.toMap(sample("identity", 0.919), new LinkedHashMap<>());
        root = EvalBaselineStore.toMap(sample("djl", 0.909), root);

        assertThat(EvalBaselineStore.parse(root, "identity").metrics().get("hybrid").mrr())
                .isCloseTo(0.919, within(0.001));
        assertThat(EvalBaselineStore.parse(root, "djl").metrics().get("hybrid").mrr())
                .isCloseTo(0.909, within(0.001));
    }

    @Test
    void parsingAnUnknownVariantNamesTheRemedy() {
        Map<String, Object> root = EvalBaselineStore.toMap(sample("identity", 0.919), new LinkedHashMap<>());

        assertThatThrownBy(() -> EvalBaselineStore.parse(root, "djl"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("djl")
                .hasMessageContaining("-Deval.baseline.update=true");
    }

    /** Metrics are rounded to 3 decimals on write so the committed file stays readable. */
    @Test
    void roundsMetricsToThreeDecimalsOnWrite() {
        Map<String, BackendMetrics> metrics = new LinkedHashMap<>();
        metrics.put("hybrid", new BackendMetrics(0.9090909090909091, 0.9191919191919192, 0.909));
        Map<String, List<String>> found = new LinkedHashMap<>();
        found.put("hybrid", List.of(Q1));
        EvalBaseline original = new EvalBaseline(CORPUS, "identity", List.of(Q1), metrics, found);

        EvalBaseline parsed = EvalBaselineStore.parse(
                EvalBaselineStore.toMap(original, new LinkedHashMap<>()), "identity");

        assertThat(parsed.metrics().get("hybrid").recall5()).isEqualTo(0.909);
        assertThat(parsed.metrics().get("hybrid").mrr()).isEqualTo(0.919);
    }

    /**
     * Covers the real serialization path, which toMap/parse alone do not: the map is dumped to YAML
     * text and loaded back. Question strings carry punctuation that YAML must quote correctly.
     * Deliberately does not touch EvalBaselineStore.SOURCE - a unit test must not dirty the repo.
     */
    @Test
    void survivesAnActualYamlDumpAndLoad() {
        Map<String, Object> root = EvalBaselineStore.toMap(sample("identity", 0.919), new LinkedHashMap<>());
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);

        String yaml = new Yaml(options).dump(root);
        Map<String, Object> reloaded = new Yaml().load(yaml);
        EvalBaseline parsed = EvalBaselineStore.parse(reloaded, "identity");

        assertThat(parsed.corpus()).isEqualTo(CORPUS);
        assertThat(parsed.questions()).containsExactly(Q1);
        assertThat(parsed.found()).containsEntry("hybrid", List.of(Q1));
        assertThat(parsed.metrics().get("hybrid").mrr()).isCloseTo(0.919, within(0.001));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=EvalBaselineStoreTest -DfailIfNoTests=false`

Expected: compilation failure, `cannot find symbol: class EvalBaselineStore`.

- [ ] **Step 3: Create `EvalBaselineStore`**

Create `src/test/java/com/example/springbootrag/eval/EvalBaselineStore.java`:

```java
package com.example.springbootrag.eval;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads and writes the committed retrieval baseline.
 *
 * <p>Reads come from the test classpath, matching {@link GoldenSet}. Writes go to the SOURCE tree,
 * not the classpath copy under {@code target/}, because a regenerated baseline that lands in
 * {@code target/} is discarded by the next build and the update silently does nothing.
 */
public final class EvalBaselineStore {

    static final String RESOURCE = "/eval/baseline-wiki.yaml";
    static final Path SOURCE = Path.of("src", "test", "resources", "eval", "baseline-wiki.yaml");
    private static final String UPDATE_HINT = "regenerate with -Deval.baseline.update=true";

    private EvalBaselineStore() {}

    /** Loads one variant's baseline from the classpath. */
    public static EvalBaseline load(String variant) {
        try (InputStream in = EvalBaselineStore.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        RESOURCE + " not found on the test classpath - " + UPDATE_HINT);
            }
            Map<String, Object> root = new Yaml().load(in);
            return parse(root, variant);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + RESOURCE, e);
        }
    }

    /** Replaces one variant's section in the source file, preserving every other section. */
    public static void write(EvalBaseline baseline) {
        Map<String, Object> existing = Files.exists(SOURCE) ? readSource() : new LinkedHashMap<>();
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        try {
            Files.createDirectories(SOURCE.getParent());
            Files.writeString(SOURCE, new Yaml(options).dump(toMap(baseline, existing)));
        } catch (IOException e) {
            throw new IllegalStateException("could not write " + SOURCE, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readSource() {
        try (InputStream in = Files.newInputStream(SOURCE)) {
            Map<String, Object> root = new Yaml().load(in);
            return root == null ? new LinkedHashMap<>() : new LinkedHashMap<>(root);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + SOURCE, e);
        }
    }

    /** Merges one variant into an existing root map. Shared keys are overwritten, others kept. */
    static Map<String, Object> toMap(EvalBaseline baseline, Map<String, Object> existing) {
        Map<String, Object> root = new LinkedHashMap<>(existing);

        Map<String, Object> corpus = new LinkedHashMap<>();
        corpus.put("projectId", baseline.corpus().projectId());
        corpus.put("projectName", baseline.corpus().projectName());
        corpus.put("docCount", baseline.corpus().docCount());
        corpus.put("chunkCount", baseline.corpus().chunkCount());
        root.put("corpus", corpus);
        root.put("questions", List.copyOf(baseline.questions()));

        Map<String, Object> metrics = new LinkedHashMap<>();
        baseline.metrics().forEach((backend, m) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("recall5", round3(m.recall5()));
            row.put("mrr", round3(m.mrr()));
            row.put("hit1", round3(m.hit1()));
            metrics.put(backend, row);
        });

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("metrics", metrics);
        section.put("found", new LinkedHashMap<>(baseline.found()));
        root.put(baseline.variant(), section);
        return root;
    }

    @SuppressWarnings("unchecked")
    static EvalBaseline parse(Map<String, Object> root, String variant) {
        Map<String, Object> corpus = (Map<String, Object>) root.get("corpus");
        if (corpus == null) {
            throw new IllegalStateException(
                    "baseline has no 'corpus' block - " + UPDATE_HINT);
        }
        CorpusFingerprint fingerprint = new CorpusFingerprint(
                ((Number) corpus.get("projectId")).longValue(),
                (String) corpus.get("projectName"),
                ((Number) corpus.get("docCount")).intValue(),
                ((Number) corpus.get("chunkCount")).intValue());

        List<String> questions = (List<String>) root.get("questions");

        Map<String, Object> section = (Map<String, Object>) root.get(variant);
        if (section == null) {
            throw new IllegalStateException("baseline has no section for reranker variant '"
                    + variant + "' - " + UPDATE_HINT);
        }

        Map<String, BackendMetrics> metrics = new LinkedHashMap<>();
        ((Map<String, Object>) section.get("metrics")).forEach((backend, value) -> {
            Map<String, Object> row = (Map<String, Object>) value;
            metrics.put(backend, new BackendMetrics(
                    ((Number) row.get("recall5")).doubleValue(),
                    ((Number) row.get("mrr")).doubleValue(),
                    ((Number) row.get("hit1")).doubleValue()));
        });

        Map<String, List<String>> found =
                new LinkedHashMap<>((Map<String, List<String>>) section.get("found"));

        return new EvalBaseline(fingerprint, variant, questions, metrics, found);
    }

    /**
     * Keeps the committed file readable at the same 3 decimals the report prints. The rounding
     * error is at most 0.0005 against a tolerance of 0.02, and it only ever lowers the floor.
     */
    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=EvalBaselineStoreTest -DfailIfNoTests=false`

Expected: `Tests run: 5, Failures: 0, Errors: 0`.

- [ ] **Step 5: Run the whole build**

Run: `./mvnw test`

Expected: `Tests run: 126, Failures: 0, Errors: 0, Skipped: 3`. `BUILD SUCCESS`.

- [ ] **Step 6: Commit (only with an explicit owner waiver)**

```bash
git add src/test/java/com/example/springbootrag/eval/EvalBaselineStore.java src/test/java/com/example/springbootrag/eval/EvalBaselineStoreTest.java
git commit -m "test(eval): add baseline YAML store with variant-preserving write"
```

---

### Task 4: Wire the gate into `WikiRetrievalEvalTest` and generate the baseline

**Requires the live stack**: Postgres and Qdrant containers up, Ollama on 11434 with `nomic-embed-text`, and the wiki imported as project `docmaster`. If any of those is missing the eval skips and this task cannot be completed - report BLOCKED rather than inventing numbers.

**Files:**
- Modify: `src/test/java/com/example/springbootrag/eval/WikiRetrievalEvalTest.java`
- Create: `src/test/resources/eval/baseline-wiki.yaml` (generated, not hand-written)

**Interfaces:**
- Consumes: everything from Tasks 1 to 3.
- Produces: the committed baseline file, and a gated eval.

- [ ] **Step 1: Add the imports and the tolerance constant**

In `WikiRetrievalEvalTest.java`, add to the import block:

```java
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
```

and add below `TOP_K`:

```java
    /**
     * A floor, not a pin. With 11 questions recall@5 and hit@1 move in steps of 1/11 = 0.091, so
     * this tolerance makes those two exact-or-better and in practice only tunes MRR: a question
     * slipping rank 1 to rank 2 costs 0.045 and fails, a slip from rank 9 to 10 costs 0.001 and
     * passes.
     */
    static final double TOLERANCE = 0.02;
```

- [ ] **Step 2: Add the variant, baseline-building, and gate helpers**

Add these three methods to `WikiRetrievalEvalTest`, directly above `requireCorpus()`:

```java
    /**
     * Baseline section name, derived from the ACTIVE bean rather than from -Deval.rerank. Deriving
     * it from the flag would let app.rerank.provider=djl in application.yml write djl numbers into
     * the identity section.
     */
    static String variantOf(Reranker active) {
        String name = active.getClass().getSimpleName();
        return switch (name) {
            case "IdentityReranker" -> "identity";
            case "DjlReranker" -> "djl";
            default -> throw new IllegalStateException("unknown reranker implementation '" + name
                    + "' - give it a baseline variant name in variantOf");
        };
    }

    /** Turns this run into the same shape the committed baseline uses. */
    private static EvalBaseline buildBaseline(ProjectSummary project, List<GoldenEntry> golden,
                                              List<BackendRun> runs, String variant) {
        CorpusFingerprint corpus = new CorpusFingerprint(
                project.id(), project.name(), project.docCount(), project.chunkCount());

        Map<String, BackendMetrics> metrics = new LinkedHashMap<>();
        Map<String, List<String>> found = new LinkedHashMap<>();
        for (BackendRun run : runs) {
            metrics.put(run.backend(), BackendMetrics.of(run.ranks(), golden.size()));
            List<String> hits = new ArrayList<>();
            for (int i = 0; i < golden.size(); i++) {
                if (run.ranks()[i] > 0) {
                    hits.add(golden.get(i).question());
                }
            }
            found.put(run.backend(), hits);
        }
        return new EvalBaseline(corpus, variant,
                golden.stream().map(GoldenEntry::question).toList(), metrics, found);
    }

    /** Regenerates the baseline instead of asserting, when -Deval.baseline.update=true is set. */
    private static void updateBaseline(EvalBaseline actual) {
        EvalBaselineStore.write(actual);
        System.out.printf("%nbaseline updated for variant '%s' -> %s%n"
                        + "assertions were SKIPPED for this run; review the diff before committing%n",
                actual.variant(), EvalBaselineStore.SOURCE);
    }
```

- [ ] **Step 3: Replace the closing assertion with the gate**

In `wikiRetrievalReport()`, replace the existing final assertion block (currently the comment plus `assertThat(runs).allSatisfy(...)`, lines 85-90) with:

```java
        // A run that quietly returns nothing must fail, not print a table of zeros.
        // Per backend, not across backends: one healthy backend must not mask five broken ones.
        assertThat(runs).allSatisfy(run ->
                assertThat(Arrays.stream(run.ranks()).anyMatch(r -> r > 0))
                        .as("backend '%s' found no golden doc for any question", run.backend())
                        .isTrue());

        String variant = variantOf(reranker);
        EvalBaseline actual = buildBaseline(project, golden, runs, variant);

        if (Boolean.getBoolean("eval.baseline.update")) {
            updateBaseline(actual);
            return;
        }

        EvalBaseline expected = EvalBaselineStore.load(variant);
        BaselineComparison.newQuestions(expected, actual).forEach(q ->
                System.out.printf("notice: golden question is new and therefore not gated: %s%n", q));

        List<BaselineComparison.Violation> violations =
                BaselineComparison.compare(expected, actual, TOLERANCE);
        assertThat(violations)
                .withFailMessage(() -> String.format(Locale.ROOT,
                        "retrieval regression against the baseline (variant '%s', tolerance %.3f):%n%s",
                        variant, TOLERANCE,
                        violations.stream()
                                .map(v -> "  [" + v.backend() + "] " + v.detail())
                                .collect(Collectors.joining("\n"))))
                .isEmpty();
```

The existing "returns nothing" assertion stays: it is a different check (a totally dead backend) and it runs before the baseline is even loaded.

- [ ] **Step 4: Confirm the stack is up**

Run:

```bash
docker start springboot-rag-postgres-1 springboot-rag-qdrant-1
docker ps --format "{{.Names}}\t{{.Status}}"
curl -sS -m 5 http://localhost:11434/api/tags
```

Expected: both containers `Up`, and the Ollama model list includes `nomic-embed-text`. If not, stop and report BLOCKED.

- [ ] **Step 5: Generate the identity baseline**

Run: `./mvnw test "-Dgroups=eval-wiki" "-DexcludedGroups=" "-Deval.baseline.update=true"`

Expected: `BUILD SUCCESS`, and the console shows `baseline updated for variant 'identity'`.

Then confirm the file exists and holds the known numbers:

```bash
cat src/test/resources/eval/baseline-wiki.yaml
```

Expected: `corpus` shows `docCount: 428` and `chunkCount: 7536`; the `identity` section shows `fts` at `0.182/0.182/0.182` and the other five backends at `0.909/0.919/0.909`. If the numbers differ, STOP and report - the corpus or the code has changed and that needs a human decision, not a new baseline.

- [ ] **Step 6: Generate the djl baseline**

Run: `./mvnw test "-Dgroups=eval-wiki" "-DexcludedGroups=" "-Deval.rerank=djl" "-Deval.baseline.update=true"`

Expected: `BUILD SUCCESS`, console shows `reranker=DjlReranker` and `baseline updated for variant 'djl'`.

Then confirm both sections coexist:

```bash
grep -n "^identity:\|^djl:\|^corpus:\|^questions:" src/test/resources/eval/baseline-wiki.yaml
```

Expected: all four keys present. The `djl` section has `rerank` and `graph` at `0.909/0.909/0.909`, and the `identity` section still holds `0.909/0.919/0.909`.

- [ ] **Step 7: Verify the gate passes against the committed baseline**

Run both, expecting `BUILD SUCCESS` from each:

```bash
./mvnw test "-Dgroups=eval-wiki" "-DexcludedGroups="
./mvnw test "-Dgroups=eval-wiki" "-DexcludedGroups=" "-Deval.rerank=djl"
```

- [ ] **Step 8: Verify the gate actually fails when it should**

Temporarily edit `src/test/resources/eval/baseline-wiki.yaml` and change the `identity` section's `hybrid` `mrr` from `0.919` to `0.999`.

Run: `./mvnw test "-Dgroups=eval-wiki" "-DexcludedGroups="`

Expected: `BUILD FAILURE`, with a message containing `retrieval regression against the baseline (variant 'identity'` and `[hybrid] MRR 0.919 is below the floor 0.979`.

**Then revert that edit** (`git checkout -- src/test/resources/eval/baseline-wiki.yaml`, or restore `0.919` by hand if the file is not yet tracked) and re-run to confirm `BUILD SUCCESS`. A gate that has never been seen to fail is not known to work.

- [ ] **Step 9: Run the whole normal build**

Run: `./mvnw test`

Expected: `Tests run: 126, Failures: 0, Errors: 0, Skipped: 3`. The wiki eval stays excluded by tag, so nothing new runs here. `BUILD SUCCESS`.

- [ ] **Step 10: Commit (only with an explicit owner waiver)**

```bash
git add src/test/java/com/example/springbootrag/eval/WikiRetrievalEvalTest.java src/test/resources/eval/baseline-wiki.yaml
git commit -m "feat(eval): gate wiki retrieval eval against a committed baseline"
```

---

### Task 5: Documentation

**Files:**
- Modify: `README.md` (the wiki corpus eval block, around line 231)
- Modify: `docs/RAG-MASTERY.md` (section 9 scorecard, row 3 and the note beneath it)
- Modify: `docs/implementation-notes.md` (append a dated entry)
- Modify: `docs/ROADMAP.md` (cross-reference the plan from the deferred CI-gate entry)

**Interfaces:**
- Consumes: the measured behaviour from Task 4.
- Produces: nothing code-level.

- [ ] **Step 1: Document the gate in README.md**

In the wiki corpus eval block, keep the existing sentence that ends "...for what that comparison
measured." and append the following four paragraphs directly after it. The fenced command block
below is literal README content: reproduce it as a real triple-backtick `bash` fence.

> This eval is a **regression gate**, not only a report: it fails when a backend drops below
> `src/test/resources/eval/baseline-wiki.yaml` by more than 0.02 on recall@5, MRR, or hit@1, or
> when any question the baseline found is no longer found at all. Each reranker variant has its own
> baseline section, since `-Deval.rerank=djl` legitimately changes the expected numbers.
>
> After re-importing the wiki, the baseline is stale by construction (chunk ids shift). The gate
> detects that from the recorded doc and chunk counts and tells you to regenerate rather than
> reporting six fake regressions:
>
> &nbsp;&nbsp;&nbsp;&nbsp;`./mvnw test "-Dgroups=eval-wiki" "-DexcludedGroups=" "-Deval.baseline.update=true"`
>
> That rewrites the current variant's section, leaves the other variant untouched, and skips the
> assertions for that run. Review the resulting diff before committing it: accepting a lower
> baseline should always be a deliberate, visible act.

In the README itself, write the command as a normal fenced `bash` block rather than the indented
inline form used above, matching the surrounding style. The `>` quoting here exists only so this
plan can show README content without nesting code fences.

- [ ] **Step 2: Update the scorecard in docs/RAG-MASTERY.md**

Change section 9 row 3 from:

```markdown
| 3 | Eval running on the realistic corpus, as a gate | 1 |
```

to:

```markdown
| 3 | Eval running on the realistic corpus, as a gate | 2 |
```

Then replace the whole "**Row 3 update (2026-07-29):**" paragraph with:

```markdown
**Row 3 update (2026-08-05): now 2.** `golden-wiki.yaml` runs against the real corpus and, as of
drill C, `WikiRetrievalEvalTest` fails the build when any backend drops more than 0.02 on
recall@5/MRR/hit@1 against `baseline-wiki.yaml`, or when a question the baseline found goes
missing. The 2026-07-29 score of 1 was withheld precisely because a report is not a gate; that
objection is now answered.

It is a 2 and not a 3-equivalent, because the gate runs only where the private wiki corpus exists,
which is one developer's machine. It cannot run in CI or on a fresh clone. Making it enforceable
needs a frozen test corpus, tracked in `ROADMAP.md`.
```

- [ ] **Step 3: Append the design record to docs/implementation-notes.md**

Append at the end of the file:

```markdown

## 2026-08-05 - Retrieval eval regression gate (drill C)

`WikiRetrievalEvalTest` now asserts against `src/test/resources/eval/baseline-wiki.yaml` instead of
only printing. Spec: `docs/superpowers/specs/2026-08-05-eval-regression-gate-design.md`.

### Why the wiki eval and not the self-corpus eval
`RetrievalEvalTest` ingests `docs/` (`RetrievalEvalTest:89`), so its corpus is this repo's own
documentation and its numbers move whenever anyone edits a doc - four files under `docs/` changed on
2026-08-05 alone. Gating it produces failures caused by writing documentation, which get ignored
within a week. The wiki corpus is frozen and reproduced its numbers exactly on a same-day re-run, so
movement there is attributable to code. The cost of that choice is that the gate can never run in CI
or on a fresh clone; it is pre-merge discipline for one machine. Tracked in `ROADMAP.md`.

### Two checks, because aggregates alone would have missed the real regression
Floors on recall@5/MRR/hit@1 with a 0.02 tolerance, PLUS a rule that no question may go from found
to missed. The second check exists because of a measured case: when the cross-encoder pushed one
question out of the top 10 on 2026-08-05, recall@5 and hit@1 did not move at all (that question had
been at rank 9, outside both windows) and MRR moved only 0.010. Any tolerance comfortable enough to
live with would have hidden it. That case is encoded as a unit test.

### Why 0.02
With 11 questions the metrics are quantized: recall@5 and hit@1 move in steps of 1/11 = 0.091, so
any tolerance below that makes them exact-or-better and only MRR is actually tuned. On the MRR
scale, a question slipping rank 1 to 2 costs 0.045 (fails), rank 9 to 10 costs 0.001 (passes).
Measured noise is currently zero, so 0.02 is headroom against nondeterminism that has not appeared,
not against observed variance.

### Variant derived from the bean, not the flag
The baseline has one section per reranker variant, keyed by the active `Reranker` bean's simple
class name rather than by `-Deval.rerank`. Deriving it from the flag would let
`app.rerank.provider=djl` set in `application.yml` write djl numbers into the identity section.

### Corpus fingerprint
The baseline records project id, name, doc count, and chunk count. A re-import shifts chunk ids and
moves every number for reasons that are not regressions; without the fingerprint that surfaces as
six simultaneous backend failures that read exactly like a real defect. The gate compares the
fingerprint first and, on mismatch, reports only that with the regeneration command. It is a
staleness check, not an integrity check: a corpus edited in place that preserves both counts is not
detected.

### Writes go to the source tree
`EvalBaselineStore.write` targets `src/test/resources/eval/baseline-wiki.yaml`, not the classpath
copy under `target/`. Writing to the classpath copy would be discarded by the next build and the
update would silently do nothing. Metrics are rounded to 3 decimals on write to keep the committed
file readable; the rounding error is at most 0.0005 against a 0.02 tolerance and only ever lowers
the floor.

### What is deliberately not gated
`FaithfulnessEvalTest` (LLM-judge output needs its own noise study first), `RetrievalEvalTest`, and
anything in CI. The full golden question list is recorded in the baseline so that adding a question
is a printed notice rather than a build failure.
```

- [ ] **Step 4: Cross-reference the plan from docs/ROADMAP.md**

In the "CI-runnable eval gate - frozen test corpus" entry, change the first bullet's opening from
"**The limitation being tracked.** The gate designed in" to
"**The limitation being tracked.** The gate built in".

- [ ] **Step 5: Verify no stale claims remain**

Run:

```bash
grep -rn "not a gate\|report, not a gate\|still no gate" README.md docs/RAG-MASTERY.md docs/LEARNINGS.md docs/implementation-notes.md
```

Expected: only historical, explicitly dated statements survive (for example the 2026-07-29 note in `RAG-MASTERY.md` section 3, which describes what was true then). Any present-tense claim that no gate exists must be corrected.

- [ ] **Step 6: Run the whole build one last time**

Run: `./mvnw test`

Expected: `Tests run: 126, Failures: 0, Errors: 0, Skipped: 3`. `BUILD SUCCESS`.

- [ ] **Step 7: Commit (only with an explicit owner waiver)**

```bash
git add README.md docs/RAG-MASTERY.md docs/implementation-notes.md docs/ROADMAP.md
git commit -m "docs(eval): record the retrieval regression gate and re-score row 3"
```

---

## Verification checklist

After all five tasks:

- [ ] `./mvnw test` is green and the three new unit test classes ran without Docker, Ollama, or the corpus
- [ ] `./mvnw test "-Dgroups=eval-wiki" "-DexcludedGroups="` passes against the committed baseline
- [ ] The same command with `-Deval.rerank=djl` passes against the djl section
- [ ] The gate was seen to FAIL against a deliberately raised baseline, and the edit was reverted
- [ ] `src/test/resources/eval/baseline-wiki.yaml` holds both variants plus one shared `corpus` and `questions` block
- [ ] `WikiRetrievalEvalTest` still injects only `SearchService`, `ProjectRepository`, and `Reranker`
- [ ] No `pom.xml` change, no new dependency
- [ ] No em-dash character in any changed file
