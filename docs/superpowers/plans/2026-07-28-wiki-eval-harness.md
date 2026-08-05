# Wiki Eval Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `src/test/resources/eval/golden-wiki.yaml` runnable, so one command reports retrieval quality for all six backends against the already-imported 428-page wiki corpus.

**Architecture:** A new JUnit test class `WikiRetrievalEvalTest` that declares no Testcontainers, so Spring picks up `application.yml` and talks to the live local stack (Postgres 5432, Qdrant 6334, Ollama 11434). It reads the corpus already imported under the project named "docmaster", runs every golden question through every backend once, keeps the results in memory, and prints three reports from that one sweep: an aggregate metric table, a per-question rank matrix, and a graph-vs-hybrid diff. `RetrievalEvalTest` and `FaithfulnessEvalTest` are untouched apart from a non-breaking `GoldenSet` overload.

**Tech Stack:** Java 21 target (Java 25 runtime), Spring Boot 3.5.6, JUnit 5, AssertJ, SnakeYAML (all already present). No new dependencies.

**Spec:** `docs/superpowers/specs/2026-07-28-wiki-eval-harness-design.md`

## Global Constraints

- **Never run `git add` or `git commit`.** Standing user rule. Every task ends at "tests verified green"; the user commits. Do not add commit steps.
- **No new dependencies.** Everything needed is already in `pom.xml`.
- **Read-only against the live database.** `WikiRetrievalEvalTest` may inject only `SearchService`, `ProjectRepository`, and (from Task 6) `Reranker`. Never inject `IngestService`. Never call `create` / `rename` / `setGroup` / `delete` / any ingest path. Adding a write to this class is a design violation, not a shortcut.
- **Build command is `./mvnw`, never `mvn`.**
- **Never use the "—" character** in code, comments, or docs. Use "-".
- Eval tests are excluded from the normal build by JUnit tag. The default `<excludedGroups>` must always list every eval tag.
- Golden-set counts: `golden.yaml` = 18 questions (self corpus), `golden-wiki.yaml` = 11 questions (wiki corpus, Section A only; Section B is empty by design).
- Comments in English.

---

## File Structure

| File | Responsibility |
|---|---|
| `src/test/java/com/example/springbootrag/eval/GoldenSet.java` | MODIFY. Loads a golden set from a named classpath resource. Gains `load(String)`; existing `load()` delegates. |
| `src/test/java/com/example/springbootrag/eval/GoldenSetTest.java` | CREATE. Fast unit test, no tag, runs in the normal build. Guards that both YAML files parse and that a bad resource name fails loudly. |
| `src/test/java/com/example/springbootrag/eval/WikiRetrievalEvalTest.java` | CREATE. The whole wiki eval: preconditions, search sweep, three reports. |
| `pom.xml` | MODIFY. Add `eval-wiki` to `<excludedGroups>`; add eval system properties so `-D` reaches the forked test JVM. |
| `README.md` | MODIFY. Run command and prerequisites. |
| `docs/LEARNINGS.md` | MODIFY. Record the measured numbers after the first real run. |
| `docs/RAG-MASTERY.md` | MODIFY. Update §3 status and scorecard row 3. |
| `docs/implementation-notes.md` | MODIFY. Decisions and deviations. |

---

### Task 1: `GoldenSet` resource overload

**Files:**
- Modify: `src/test/java/com/example/springbootrag/eval/GoldenSet.java`
- Test: `src/test/java/com/example/springbootrag/eval/GoldenSetTest.java` (create)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `GoldenSet.load(String resource)` returning `List<GoldenEntry>`; `GoldenSet.load()` unchanged in behaviour (delegates to `/eval/golden.yaml`). `GoldenEntry` is the existing record `(String question, String expectedDocId, String expectedHeadingPath)`.

This test carries no JUnit tag on purpose, so it runs in the normal build and catches a broken YAML file in seconds without any stack.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/springbootrag/eval/GoldenSetTest.java`:

```java
package com.example.springbootrag.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fast guard on the golden-set files. Deliberately untagged so it runs in the normal build:
 * a malformed YAML file should fail here, not two minutes into an eval run.
 */
class GoldenSetTest {

    @Test
    void loadsTheDefaultSelfCorpusSet() {
        List<GoldenEntry> entries = GoldenSet.load();

        assertThat(entries).isNotEmpty();
        assertThat(entries).allSatisfy(e -> {
            assertThat(e.question()).isNotBlank();
            assertThat(e.expectedDocId()).isNotBlank();
        });
    }

    @Test
    void loadsTheWikiSetByResourceName() {
        List<GoldenEntry> entries = GoldenSet.load("/eval/golden-wiki.yaml");

        // Section A has 11 verified questions today; Section B is empty and may grow later,
        // so assert a floor rather than an exact count.
        assertThat(entries).hasSizeGreaterThanOrEqualTo(11);
        assertThat(entries).allSatisfy(e -> {
            assertThat(e.question()).isNotBlank();
            assertThat(e.expectedDocId()).isNotBlank();
        });
        assertThat(entries.get(0).expectedDocId()).isEqualTo("E-invoicing");
        assertThat(entries.get(0).expectedHeadingPath()).isNull();
    }

    @Test
    void unknownResourceFailsLoudlyAndNamesTheFile() {
        assertThatThrownBy(() -> GoldenSet.load("/eval/does-not-exist.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does-not-exist.yaml");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test "-Dtest=GoldenSetTest"`

Expected: compilation failure, `cannot find symbol: method load(java.lang.String)`.

- [ ] **Step 3: Add the overload**

Replace the body of `src/test/java/com/example/springbootrag/eval/GoldenSet.java` with:

```java
package com.example.springbootrag.eval;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

public final class GoldenSet {

    private static final String DEFAULT_RESOURCE = "/eval/golden.yaml";

    private GoldenSet() {}

    /** The self-corpus golden set (this project's own docs). */
    public static List<GoldenEntry> load() {
        return load(DEFAULT_RESOURCE);
    }

    /** Loads any golden set from the test classpath, e.g. "/eval/golden-wiki.yaml". */
    public static List<GoldenEntry> load(String resource) {
        try (InputStream in = GoldenSet.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " not found on the test classpath");
            }
            List<Map<String, String>> raw = new Yaml().load(in);
            return raw.stream()
                    .map(m -> new GoldenEntry(
                            m.get("question"),
                            m.get("expectedDocId"),
                            m.get("expectedHeadingPath")))
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("could not load golden set " + resource, e);
        }
    }
}
```

Both failure paths now name the resource, which is what the third test asserts.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test "-Dtest=GoldenSetTest"`

Expected: PASS, 3 tests.

- [ ] **Step 5: Verify nothing else broke**

Run: `./mvnw test`

Expected: the full suite still green (105 tests before this change, 108 after: 3 new). Eval tests remain excluded.

---

### Task 2: `WikiRetrievalEvalTest` skeleton, tag exclusion, and preconditions

**Files:**
- Create: `src/test/java/com/example/springbootrag/eval/WikiRetrievalEvalTest.java`
- Modify: `pom.xml` (`<excludedGroups>` in `<properties>`, and `<systemPropertyVariables>` in the surefire plugin)

**Interfaces:**
- Consumes: `GoldenSet.load(String)` from Task 1.
- Produces: the test class with constants `GOLDEN`, `TOP_K`, `BACKENDS`; a private method `requireCorpus()` returning `ProjectSummary`; a `@Test` method `wikiRetrievalReport()`. Later tasks add private helpers to this same class.
- Existing main-code types used: `SearchService.search(String type, String query, int topK, List<Long> projectIds, List<String> docIds)` returning `List<SearchHit>`; `ProjectRepository.listWithCounts()` returning `List<ProjectSummary>`; `ProjectSummary(long id, String name, String groupName, int docCount, int chunkCount)`.

- [ ] **Step 1: Exclude the new tag from the normal build**

In `pom.xml`, inside `<properties>`, change:

```xml
<excludedGroups>eval,eval-judge</excludedGroups>
```

to:

```xml
<excludedGroups>eval,eval-judge,eval-wiki</excludedGroups>
```

Without this, the next `./mvnw test` would try to reach a live database.

- [ ] **Step 2: Write the test class**

Create `src/test/java/com/example/springbootrag/eval/WikiRetrievalEvalTest.java`:

```java
package com.example.springbootrag.eval;

import com.example.springbootrag.repository.ProjectRepository;
import com.example.springbootrag.service.SearchService;
import com.example.springbootrag.web.dto.ProjectSummary;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retrieval quality report for the imported wiki corpus (golden-wiki.yaml).
 *
 * <p>Unlike {@code RetrievalEvalTest} this builds NO corpus of its own: the wiki clone is private
 * and re-embedding 7,536 chunks per run costs hours. It therefore declares no Testcontainers, so
 * Spring uses application.yml and queries the LIVE local stack, reading whatever is already
 * imported.
 *
 * <p>READ-ONLY BY CONSTRUCTION: only SearchService and ProjectRepository are injected, so there is
 * no code path here that can write or delete. Do NOT add IngestService to this class.
 *
 * <p>Prereqs: Postgres 5432 + Qdrant 6334 up, Ollama 11434 with nomic-embed-text pulled, and the
 * wiki already imported into a project named "docmaster" (override with -Deval.wiki.project=NAME).
 *
 * <p>Run: ./mvnw test "-Dgroups=eval-wiki" "-DexcludedGroups="
 */
@SpringBootTest
@Tag("eval-wiki")
@TestPropertySource(properties = "spring.sql.init.mode=never")
class WikiRetrievalEvalTest {

    static final String GOLDEN = "/eval/golden-wiki.yaml";
    static final int TOP_K = 10;
    static final List<String> BACKENDS =
            List.of("fts", "pgvector", "qdrant", "hybrid", "rerank", "graph");

    @Autowired SearchService searchService;
    @Autowired ProjectRepository projects;

    @Test
    void wikiRetrievalReport() {
        ProjectSummary project = requireCorpus();
        List<GoldenEntry> golden = GoldenSet.load(GOLDEN);
        assertThat(golden).isNotEmpty();

        System.out.printf("%nWiki retrieval eval: project=%s (id=%d, %d docs, %d chunks), "
                        + "%d questions, topK=%d%n",
                project.name(), project.id(), project.docCount(), project.chunkCount(),
                golden.size(), TOP_K);
    }

    /**
     * Resolves the corpus project by NAME (never a hardcoded id) and SKIPS the test when it is
     * absent. A fresh clone of this repo can never have the private wiki, so a hard failure there
     * would be permanent and meaningless.
     */
    private ProjectSummary requireCorpus() {
        String name = System.getProperty("eval.wiki.project", "docmaster");

        List<ProjectSummary> all;
        try {
            all = projects.listWithCounts();
        } catch (DataAccessException e) {
            return Assumptions.abort(
                    "Postgres is not reachable - start the stack before running the wiki eval. "
                            + "Cause: " + e.getMessage());
        }

        Optional<ProjectSummary> found = all.stream()
                .filter(p -> p.name().equalsIgnoreCase(name))
                .findFirst();
        Assumptions.assumeTrue(found.isPresent(),
                "no project named '" + name + "' - import the wiki first, or pass "
                        + "-Deval.wiki.project=<name>. Projects present: "
                        + all.stream().map(ProjectSummary::name).toList());

        ProjectSummary project = found.get();
        Assumptions.assumeTrue(project.chunkCount() > 0,
                "project '" + name + "' has 0 chunks - nothing to evaluate");
        return project;
    }
}
```

Note on `spring.sql.init.mode=never`: the app normally re-runs `schema.sql` on every boot. It is idempotent, but a read-only report has no business touching schema.

Note on `Assumptions.abort(String)`: it is declared `<V> V abort(String)`, so `return Assumptions.abort(...)` compiles and satisfies the return type.

- [ ] **Step 3: Verify the normal build ignores it**

Run: `./mvnw test`

Expected: green, and `WikiRetrievalEvalTest` does not appear in the run (excluded by tag). If it runs, Step 1 was not applied.

- [ ] **Step 4: Verify the skip path AND that `-D` reaches the test JVM**

Run:

```
./mvnw test "-Dgroups=eval-wiki" "-DexcludedGroups=" "-Deval.wiki.project=no-such-project"
```

Expected: 1 test, **skipped**, with the message naming `no-such-project` and listing the projects that do exist.

This step doubles as the empirical check that Surefire forwards `-D` into the forked JVM. If the test instead ran against `docmaster` (or skipped with a message naming `docmaster`), the property did not propagate. In that case apply Step 5; otherwise skip Step 5.

- [ ] **Step 5: ONLY IF the property did not propagate - pass it explicitly**

In `pom.xml` `<properties>`, add defaults:

```xml
<eval.wiki.project>docmaster</eval.wiki.project>
<eval.rerank></eval.rerank>
```

and in the surefire plugin's existing `<systemPropertyVariables>` block (the one that already pins `api.version`), add:

```xml
<eval.wiki.project>${eval.wiki.project}</eval.wiki.project>
<eval.rerank>${eval.rerank}</eval.rerank>
```

Defining the defaults in `<properties>` matters: without them the `${...}` placeholders would resolve to the literal string. Re-run Step 4 and confirm the skip message now names `no-such-project`.

- [ ] **Step 6: Verify the happy path reaches the corpus**

Start the stack and Ollama, then run:

```
./mvnw test "-Dgroups=eval-wiki" "-DexcludedGroups="
```

Expected: 1 test PASSES and prints a header like:

```
Wiki retrieval eval: project=docmaster (id=5, 428 docs, 7536 chunks), 11 questions, topK=10
```

If it skips with "no project named 'docmaster'", check the actual project name in the printed list and re-run with `-Deval.wiki.project=<that name>`.

---

### Task 3: Search sweep and aggregate metric table

**Files:**
- Modify: `src/test/java/com/example/springbootrag/eval/WikiRetrievalEvalTest.java`

**Interfaces:**
- Consumes: `requireCorpus()`, `GOLDEN`, `TOP_K`, `BACKENDS` from Task 2.
- Produces: the nested record `BackendRun(String backend, List<List<SearchHit>> hits, int[] ranks)`; private methods `runAll(List<GoldenEntry>, long)` returning `List<BackendRun>`, `rankOfExpected(List<SearchHit>, GoldenEntry)` returning `int` (1-based, 0 = miss), and `printAggregate(List<BackendRun>, int)`. Tasks 4 and 5 read `BackendRun` and reuse `truncate` from Task 4.

The sweep runs once and every report is derived from the stored result, so adding reports costs no extra searches.

- [ ] **Step 1: Add the sweep, the rank rule, and the aggregate report**

Add these imports to `WikiRetrievalEvalTest`:

```java
import com.example.springbootrag.model.SearchHit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
```

Add the record and methods inside the class:

```java
    /** One backend's full sweep: hits per question, plus the rank of the expected doc. */
    record BackendRun(String backend, List<List<SearchHit>> hits, int[] ranks) {}

    /** Runs every golden question through every backend once, scoped to the corpus project. */
    private List<BackendRun> runAll(List<GoldenEntry> golden, long projectId) {
        List<BackendRun> runs = new ArrayList<>();
        for (String backend : BACKENDS) {
            List<List<SearchHit>> hits = new ArrayList<>();
            int[] ranks = new int[golden.size()];
            for (int i = 0; i < golden.size(); i++) {
                GoldenEntry entry = golden.get(i);
                List<SearchHit> result = searchService.search(
                        backend, entry.question(), TOP_K, List.of(projectId), List.of());
                hits.add(result);
                ranks[i] = rankOfExpected(result, entry);
            }
            runs.add(new BackendRun(backend, hits, ranks));
        }
        return runs;
    }

    /**
     * 1-based rank of the expected document, 0 when absent from the top K.
     * Same rule as RetrievalEvalTest: docId must match and, when the golden entry pins a heading
     * path, the hit's heading path must start with it.
     */
    private static int rankOfExpected(List<SearchHit> hits, GoldenEntry e) {
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            boolean docMatch = h.docId().equals(e.expectedDocId());
            boolean headingMatch = e.expectedHeadingPath() == null
                    || (h.headingPath() != null && h.headingPath().startsWith(e.expectedHeadingPath()));
            if (docMatch && headingMatch) {
                return i + 1;
            }
        }
        return 0;
    }

    /** recall@5 is counted inside the first 5 of the TOP_K fetched, matching RetrievalEvalTest. */
    private static void printAggregate(List<BackendRun> runs, int questionCount) {
        System.out.printf("%n%-10s %10s %10s %10s%n", "backend", "recall@5", "MRR", "hit@1");
        for (BackendRun run : runs) {
            double recall5 = 0, mrr = 0, hit1 = 0;
            for (int rank : run.ranks()) {
                if (rank >= 1 && rank <= 5) recall5++;
                if (rank >= 1) mrr += 1.0 / rank;
                if (rank == 1) hit1++;
            }
            System.out.printf(Locale.ROOT, "%-10s %10.3f %10.3f %10.3f%n",
                    run.backend(), recall5 / questionCount, mrr / questionCount, hit1 / questionCount);
        }
    }
```

- [ ] **Step 2: Call it from the test method and add the anti-silent-failure assertions**

Replace the body of `wikiRetrievalReport()` with:

```java
    @Test
    void wikiRetrievalReport() {
        ProjectSummary project = requireCorpus();
        List<GoldenEntry> golden = GoldenSet.load(GOLDEN);
        assertThat(golden).isNotEmpty();

        System.out.printf("%nWiki retrieval eval: project=%s (id=%d, %d docs, %d chunks), "
                        + "%d questions, topK=%d%n",
                project.name(), project.id(), project.docCount(), project.chunkCount(),
                golden.size(), TOP_K);

        List<BackendRun> runs = runAll(golden, project.id());

        printAggregate(runs, golden.size());

        // A run that quietly returns nothing must fail, not print a table of zeros.
        assertThat(runs).hasSize(BACKENDS.size());
        assertThat(runs).allSatisfy(run -> assertThat(run.hits()).hasSize(golden.size()));
        assertThat(runs.stream().anyMatch(run -> Arrays.stream(run.ranks()).anyMatch(r -> r > 0)))
                .as("every backend missed every question - wrong project scope or empty corpus?")
                .isTrue();
    }
```

- [ ] **Step 3: Run it**

Run: `./mvnw test "-Dgroups=eval-wiki" "-DexcludedGroups="`

Expected: PASS, printing the header plus a six-row table, for example:

```
backend      recall@5        MRR      hit@1
fts             0.636      0.548      0.455
pgvector        0.909      0.812      0.727
qdrant          0.909      0.812      0.727
hybrid          1.000      0.917      0.818
rerank          1.000      0.917      0.818
graph           1.000      0.917      0.818
```

The actual numbers are unknown until this runs; that is the point of the task. Record whatever comes out.

- [ ] **Step 4: Sanity-check the numbers before believing them**

If every backend shows 0.000, the assertion in Step 2 will have failed already. If `fts` is 0.000 but the vector backends are healthy, check that the Postgres FTS index covers the wiki project. If `qdrant` alone is 0.000, Qdrant is up but may hold no points for this project.

---

### Task 4: Per-question rank matrix

**Files:**
- Modify: `src/test/java/com/example/springbootrag/eval/WikiRetrievalEvalTest.java`

**Interfaces:**
- Consumes: `BackendRun` and the populated `List<BackendRun>` from Task 3.
- Produces: private methods `printMatrix(List<GoldenEntry>, List<BackendRun>)` and `truncate(String, int)` returning `String`. Task 5 calls `truncate`.

- [ ] **Step 1: Add the matrix printer**

Add to `WikiRetrievalEvalTest`:

```java
    /** One row per question, one column per backend, showing the rank of the expected doc. */
    private static void printMatrix(List<GoldenEntry> golden, List<BackendRun> runs) {
        System.out.printf("%nrank of expected doc per question (0 = miss)%n");
        System.out.printf("%-46s", "question");
        for (BackendRun run : runs) {
            System.out.printf(" %7s", run.backend());
        }
        System.out.println();

        for (int i = 0; i < golden.size(); i++) {
            System.out.printf("%-46s", truncate(golden.get(i).question(), 44));
            for (BackendRun run : runs) {
                System.out.printf(" %7d", run.ranks()[i]);
            }
            System.out.println();
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
```

- [ ] **Step 2: Call it after the aggregate table**

In `wikiRetrievalReport()`, directly after `printAggregate(runs, golden.size());` add:

```java
        printMatrix(golden, runs);
```

- [ ] **Step 3: Run it**

Run: `./mvnw test "-Dgroups=eval-wiki" "-DexcludedGroups="`

Expected: PASS, and after the aggregate table a matrix like:

```
rank of expected doc per question (0 = miss)
question                                           fts pgvector  qdrant  hybrid  rerank   graph
Which two electronic-invoice formats are us...       3        1       1       1       1       1
From when is e-invoicing mandatory in Germany?       1        1       1       1       1       1
...
```

Every question must appear exactly once, with 6 numeric columns and no ragged rows.

---

### Task 5: Graph vs hybrid diff

**Files:**
- Modify: `src/test/java/com/example/springbootrag/eval/WikiRetrievalEvalTest.java`

**Interfaces:**
- Consumes: `BackendRun` (Task 3), `truncate` (Task 4).
- Produces: private methods `printGraphVsHybrid(List<GoldenEntry>, List<BackendRun>)` and `keys(List<SearchHit>)` returning `List<String>`.

This automates the open finding in `LEARNINGS.md` §14 ("structural graph returned an identical top-10 to hybrid on every query tried"). Two different comparisons, because they answer different questions:

- **expected-doc rank differs** - the graph backend ranks the golden document higher or lower than hybrid. This is what moves the metrics.
- **full top-10 differs** - the ordered `(docId, chunkIndex)` list is not identical. The literal §14 claim, and it can differ even when the expected-doc rank does not.

- [ ] **Step 1: Add the diff printer**

Add to `WikiRetrievalEvalTest`:

```java
    /** Identity of a result list: the ordered (docId, chunkIndex) pairs. */
    private static List<String> keys(List<SearchHit> hits) {
        return hits.stream().map(h -> h.docId() + "#" + h.chunkIndex()).toList();
    }

    /**
     * Re-tests the LEARNINGS section 14 finding over the whole golden set instead of by hand.
     * Reports both comparisons and names every question where either one differs.
     */
    private static void printGraphVsHybrid(List<GoldenEntry> golden, List<BackendRun> runs) {
        BackendRun hybrid = runs.stream()
                .filter(r -> r.backend().equals("hybrid")).findFirst().orElseThrow();
        BackendRun graph = runs.stream()
                .filter(r -> r.backend().equals("graph")).findFirst().orElseThrow();

        int rankDiffers = 0;
        int identicalTop10 = 0;
        List<String> notes = new ArrayList<>();

        for (int i = 0; i < golden.size(); i++) {
            boolean sameOrder = keys(hybrid.hits().get(i)).equals(keys(graph.hits().get(i)));
            if (sameOrder) {
                identicalTop10++;
            }
            String question = truncate(golden.get(i).question(), 60);
            if (hybrid.ranks()[i] != graph.ranks()[i]) {
                rankDiffers++;
                notes.add(String.format("  %s: hybrid=rank %d, graph=rank %d%s",
                        question, hybrid.ranks()[i], graph.ranks()[i],
                        sameOrder ? "" : "  (top-10 order differs)"));
            } else if (!sameOrder) {
                notes.add(String.format("  %s: same rank %d, but top-10 order differs",
                        question, hybrid.ranks()[i]));
            }
        }

        System.out.printf("%ngraph vs hybrid: expected-doc rank differs on %d of %d; "
                        + "full top-10 identical on %d of %d%n",
                rankDiffers, golden.size(), identicalTop10, golden.size());
        notes.forEach(System.out::println);
    }
```

- [ ] **Step 2: Call it after the matrix**

In `wikiRetrievalReport()`, directly after `printMatrix(golden, runs);` add:

```java
        printGraphVsHybrid(golden, runs);
```

- [ ] **Step 3: Run it**

Run: `./mvnw test "-Dgroups=eval-wiki" "-DexcludedGroups="`

Expected: PASS, ending with a line like:

```
graph vs hybrid: expected-doc rank differs on 0 of 11; full top-10 identical on 11 of 11
```

If the counts differ from that, the listed questions are exactly the graph-advantage candidates that `golden-wiki.yaml` Section B has been missing. Note them; do not add them to Section B in this task.

---

### Task 6: Optional cross-encoder run (`-Deval.rerank=djl`)

**Files:**
- Modify: `src/test/java/com/example/springbootrag/eval/WikiRetrievalEvalTest.java`

**Interfaces:**
- Consumes: everything from Tasks 2 to 5.
- Produces: a `@DynamicPropertySource` method `rerankOverride(DynamicPropertyRegistry)`, and an injected `Reranker` field used only to print the effective implementation name.

Default `app.rerank.provider=""` gives `IdentityReranker`. `LEARNINGS.md` §14 concluded graph expansion has no teeth without a real cross-encoder, so a default run trivially reproduces "graph == hybrid". This makes the "with teeth" run one flag away.

- [ ] **Step 1: Add the override and print the effective reranker**

Add imports:

```java
import com.example.springbootrag.rerank.Reranker;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
```

Add to the class:

```java
    /**
     * Optional cross-encoder run: -Deval.rerank=djl maps onto app.rerank.provider.
     * Blank (the default) leaves the no-op IdentityReranker in place.
     */
    @DynamicPropertySource
    static void rerankOverride(DynamicPropertyRegistry registry) {
        registry.add("app.rerank.provider", () -> System.getProperty("eval.rerank", ""));
    }

    @Autowired Reranker reranker;
```

And extend the header print in `wikiRetrievalReport()`, immediately after the existing header `printf`:

```java
        System.out.printf("reranker=%s%n", reranker.getClass().getSimpleName());
```

Printing the concrete class is the proof that the flag took effect, rather than trusting it.

- [ ] **Step 2: Verify the default is unchanged**

Run: `./mvnw test "-Dgroups=eval-wiki" "-DexcludedGroups="`

Expected: PASS, header shows `reranker=IdentityReranker`, and the metrics match Task 5's run.

- [ ] **Step 3: Verify the cross-encoder run**

Run: `./mvnw test "-Dgroups=eval-wiki" "-DexcludedGroups=" "-Deval.rerank=djl"`

Expected: PASS, header shows `reranker=DjlReranker`. The first ever djl run downloads `BAAI/bge-reranker-base` (roughly 1.1 GB) and the `rerank` and `graph` backends get much slower, since each query cross-encodes up to `app.rerank.candidates` = 50 candidates on CPU. Allow several minutes.

If the header still shows `IdentityReranker`, the `-D` did not reach the forked JVM: apply Task 2 Step 5 (the `<systemPropertyVariables>` fallback), which also covers `eval.rerank`.

- [ ] **Step 4: Record both result sets**

Keep the two console outputs (identity and djl). Task 7 writes them into the docs, and the pair is the actual answer to "does the reranker earn its latency on this corpus".

---

### Task 7: Documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/LEARNINGS.md`
- Modify: `docs/RAG-MASTERY.md`
- Modify: `docs/implementation-notes.md`

**Interfaces:**
- Consumes: the console output captured in Task 6 Step 4.
- Produces: no code.

- [ ] **Step 1: README run instructions**

In the section that documents the existing eval commands, add:

```markdown
Wiki corpus eval (real 428-page corpus, live stack - NOT Testcontainers):

    ./mvnw test "-Dgroups=eval-wiki" "-DexcludedGroups="

Prereqs: Postgres + Qdrant up, Ollama with nomic-embed-text, and the wiki already imported into
a project named "docmaster" (override with `-Deval.wiki.project=<name>`). The test is read-only
and skips itself when the corpus is absent. Add `-Deval.rerank=djl` to run with the real
cross-encoder instead of the no-op reranker.
```

- [ ] **Step 2: LEARNINGS numbers**

In `docs/LEARNINGS.md` §11 (Evaluation), add the measured wiki table next to the existing
self-corpus numbers, and in §14 replace the by-hand phrasing of the graph-vs-hybrid finding with
the measured counts from Task 5 (both comparisons, and whether the djl run changed the answer).

- [ ] **Step 3: RAG-MASTERY status**

In `docs/RAG-MASTERY.md`:
- §3 "Current state here": the wiki golden set is now runnable; delete the sentence saying it has
  no way to run. Keep the note that no regression gate exists yet.
- §9 scorecard row 3 ("Eval running on the realistic corpus, as a gate"): raise from 1 toward 2,
  but stay at 1 while there is still no gate. State that explicitly.
- §11 "next three moves": mark move 1 done, so moves 2 and 3 are the remaining queue.

- [ ] **Step 4: implementation-notes**

Add a section recording: the live-stack-instead-of-Testcontainers decision and why re-import was
rejected; the skip-not-fail precondition rule; the read-only-by-construction constraint; whether
Surefire propagated `-D` or the pom fallback was needed; and the measured numbers.

- [ ] **Step 5: Final verification**

Run: `./mvnw test`

Expected: full suite green, eval tags still excluded, `GoldenSetTest` included.

Then report to the user, and leave committing to them.

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
|---|---|
| §4a `GoldenSet.load(String)` | Task 1 |
| §4b `WikiRetrievalEvalTest`, project-by-name, scoped search, rank rule | Tasks 2, 3 |
| §4c pom `excludedGroups` | Task 2 Step 1 |
| §5 skip-not-fail preconditions | Task 2 Step 2, verified Step 4 |
| §6 read-only guarantees | Global Constraints, Task 2 Step 2 (injection list, `sql.init.mode=never`) |
| §7 report part 1 aggregate | Task 3 |
| §7 report part 2 rank matrix | Task 4 |
| §7 report part 3 graph vs hybrid, both comparisons | Task 5 |
| §7 recall@5 at topK=10 note | Task 3 Step 1 comment |
| §8 `-Deval.rerank=djl` plus propagation check | Task 6, with the fallback in Task 2 Step 5 |
| §9 cost of a run | Task 6 Step 3 note |
| §10 two anti-silent-failure assertions | Task 3 Step 2 |
| §11 out of scope | No tasks, correct |
| §12 follow-up docs | Task 7 |

No gaps.

**Placeholder scan:** none. Every code step carries complete code. The two conditional steps
(Task 2 Step 5, Task 6 Step 3 fallback) state their exact trigger and exact XML.

**Type consistency:** `BackendRun(String backend, List<List<SearchHit>> hits, int[] ranks)` is
defined once in Task 3 and used unchanged in Tasks 4 and 5. `truncate(String, int)` is defined in
Task 4 and reused in Task 5. `rankOfExpected` returns a 1-based rank with 0 for a miss everywhere.
`ProjectSummary` accessors (`id()`, `name()`, `docCount()`, `chunkCount()`) match the record.
`SearchService.search(String, String, int, List<Long>, List<String>)` matches the main-code
signature.

**Deviation from the skill template:** no commit steps anywhere, per the standing user rule that
the agent never runs `git add` or `git commit`.
