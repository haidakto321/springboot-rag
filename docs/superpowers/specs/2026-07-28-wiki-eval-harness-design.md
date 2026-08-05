# Wiki Eval Harness - Design

Date: 2026-07-28
Status: Approved (brainstorming output)
Scope: Make `golden-wiki.yaml` runnable against the already-imported 428-page wiki corpus and
report retrieval quality per backend. Faithfulness judging on the wiki, a regression gate, and
Section-B graph-advantage hunting are explicit non-goals for this build.

## 1. Goal

`src/test/resources/eval/golden-wiki.yaml` was committed on 2026-07-06 with **11 verified
coverage questions** against the imported wiki (project "docmaster", 428 docs / 7,536 chunks),
but **no code can run it**. `GoldenSet.load()` hardcodes `/eval/golden.yaml`, and
`RetrievalEvalTest` builds its own throwaway corpus from `docs/` inside Testcontainers. So the
only realistic corpus in this project is currently unmeasured.

Goal: one command that prints retrieval quality for all six backends on the real corpus, plus
the per-question detail needed to decide what to fix next.

This is step 1 of `docs/RAG-MASTERY.md` §11 ("the next three moves"). It ships no user-facing
feature; it converts the wiki into a measurable laboratory so later retrieval changes are
provable instead of arguable.

Note: earlier notes (`memory`, commit message for 07967b5) say "13 questions". The file
actually contains **11**. This spec uses 11.

## 2. Constraint that drives the whole design

The wiki corpus **cannot be rebuilt at test time**:

- The wiki clone path is private and deliberately not in the repo (privacy rule).
- Re-embedding 7,536 chunks per run costs hours on this box.

Therefore the eval must query the corpus **already imported into the live local stack**. A
Spring Boot test that declares no `@Testcontainers` and no datasource `@DynamicPropertySource`
inherits `application.yml`, which points at localhost Postgres 5432 / Qdrant 6334 / Ollama
11434. That is the mechanism.

Consequence: this test reads the developer's real dev database. Section 6 covers the
read-only guarantees that follow from that.

## 3. Chosen shape: a separate test class

Three shapes were considered:

| Option | Verdict |
|---|---|
| **New `WikiRetrievalEvalTest`, no containers, live stack** | **Chosen.** Clean split: one test = containers + self-corpus, other = live stack + wiki. Costs ~40 lines of duplicated metric loop. |
| Parameterise `RetrievalEvalTest` with `-Deval.corpus=self\|wiki` | Rejected. `@Testcontainers` / `@Container` are class-level and static, so containers still start in wiki mode, and the datasource would still point at them. |
| Abstract base + two subclasses | Rejected for now. DRYest, but refactors a currently-working test for one extra corpus. Revisit if a third corpus appears. |

## 4. Components

### 4a. `GoldenSet` (modify)

```java
public static List<GoldenEntry> load() { return load("/eval/golden.yaml"); }
public static List<GoldenEntry> load(String resource) { ... }
```

Non-breaking: `RetrievalEvalTest` and `FaithfulnessEvalTest` keep calling `load()`.

### 4b. `WikiRetrievalEvalTest` (new)

```java
@SpringBootTest
@Tag("eval-wiki")
@TestPropertySource(properties = "spring.sql.init.mode=never")
class WikiRetrievalEvalTest {
    static final String GOLDEN   = "/eval/golden-wiki.yaml";
    static final List<String> BACKENDS = List.of("fts", "pgvector", "qdrant", "hybrid", "rerank", "graph");
    static final int TOP_K = 10;

    @Autowired SearchService searchService;
    @Autowired ProjectRepository projects;
}
```

- Project resolved **by name, never by hardcoded id**: `System.getProperty("eval.wiki.project",
  "docmaster")`, looked up through `ProjectRepository.listWithCounts()` which returns
  `ProjectSummary(id, name, groupName, docCount, chunkCount)`.
- Every search is project-scoped:
  `searchService.search(backend, question, TOP_K, List.of(projectId), List.of())`.
- Hit detection reuses the existing rule from `RetrievalEvalTest.rankOfExpected`: `docId` must
  match, and `expectedHeadingPath` (when non-null) must be a prefix of the hit's heading path.
  Rank is 1-based; 0 means not found in the top 10.

### 4c. `pom.xml` (modify)

`<excludedGroups>eval,eval-judge</excludedGroups>` becomes `eval,eval-judge,eval-wiki`, so the
normal build never tries to reach a live stack.

Run command:

```
./mvnw test "-Dgroups=eval-wiki" "-DexcludedGroups="
```

## 5. Preconditions - skip, never fail

If the stack is down, the named project is missing, or its `chunkCount` is 0, the test calls
JUnit `Assumptions.abort(...)` with a message naming what to start or import. It does **not**
fail.

Reason: the corpus is private and local. A fresh clone of this repo can never have it, so a
red build would be permanent and meaningless. Combined with the tag exclusion, the test runs
only when explicitly asked for.

## 6. Read-only guarantees

The test queries the developer's real dev database, so the design constrains it structurally
rather than by convention:

- It autowires **only** `SearchService` and `ProjectRepository`. It never holds
  `IngestService`, so it has no path to write or delete chunks.
- `@TestPropertySource(properties = "spring.sql.init.mode=never")` prevents the usual
  boot-time `schema.sql` re-run. The script is idempotent, but a read-only report has no
  business touching schema.
- No project mutation (`create` / `rename` / `setGroup` / `delete`) anywhere in the class.

If a future change needs to write, it belongs in a different test class, not here.

## 7. Report format

Three parts, printed to stdout in the style of the existing eval report.

```
Wiki retrieval eval: project=docmaster (id=5, 428 docs, 7536 chunks), 11 questions, topK=10

backend      recall@5        MRR      hit@1
fts             0.xxx      0.xxx      0.xxx
pgvector        0.xxx      0.xxx      0.xxx
qdrant          0.xxx      0.xxx      0.xxx
hybrid          0.xxx      0.xxx      0.xxx
rerank          0.xxx      0.xxx      0.xxx
graph           0.xxx      0.xxx      0.xxx

rank of expected doc per question (0 = miss)
question                                        fts  pgvec  qdrant  hybrid  rerank  graph
Which two electronic-invoice formats...           3      1       1       1       1      1
...

graph vs hybrid: expected-doc rank differs on N of 11; full top-10 identical on M of 11
  <question>: hybrid=rank 4, graph=rank 1  (top-10 order differs)
```

"Differ" is measured two ways, because they answer different questions and the §14 claim is
about the second:

- **Expected-doc rank differs** - the graph backend ranks the golden document higher or lower
  than hybrid. This is what moves the metrics.
- **Full top-10 differs** - the ordered list of `(docId, chunkIndex)` is not identical. This
  is the stronger, literal form of the §14 finding, and can be true even when the expected-doc
  rank is unchanged.

Note on `recall@5` at `topK=10`: retrieval fetches 10 but recall is counted within the first
5, exactly as `RetrievalEvalTest` already does. Kept identical so the two reports compare.

Why each part:

1. **Aggregate table** - baseline, same three metrics and same layout as the self-corpus
   report, so the two corpora are directly comparable.
2. **Per-question rank matrix** - converts one bad average into a named list of which
   questions fail on which backend. This is the input for the next fix.
3. **Graph vs hybrid diff** - automates the open finding in `LEARNINGS.md` §14 ("structural
   graph returned an identical top-10 to hybrid on every query tried"). Re-tests it over the
   whole set instead of by hand, and names the differing questions when there are any.

## 8. Optional "with teeth" run

Default `app.rerank.provider=""` means `IdentityReranker`. `LEARNINGS.md` §14 concluded that
graph expansion has no teeth without a real cross-encoder, so a default run will trivially
reproduce "graph == hybrid". The test therefore accepts an override:

```
./mvnw test "-Dgroups=eval-wiki" "-DexcludedGroups=" -Deval.rerank=djl
```

mapped onto `app.rerank.provider` through `@DynamicPropertySource`.

Open item, resolved at implementation time: whether Surefire propagates `-D` into the forked
test JVM. Verify on the first run by printing the effective reranker; if it does not
propagate, add the mapping to the existing `<systemPropertyVariables>` block in `pom.xml`
(already used there to pin `api.version`).

## 9. Cost of a run

11 questions x 6 backends = 66 searches. Each embeds the query once via Ollama
(`nomic-embed-text`, fast). `graph` with the default `edges=structural` performs **no** LLM
call at query time (entity extraction at query time only applies to `semantic` / `both`).
With `-Deval.rerank=djl`, the `rerank` and `graph` backends additionally cross-encode up to
`app.rerank.candidates` = 50 candidates per query, which is the slow part on this hardware.

## 10. Testing the eval

An eval reporter is a report, so it gets no unit tests. Verification is running it and reading
the output, plus two assertions so a silently-broken run fails instead of printing zeros:

- the loaded golden set is non-empty;
- every question produced a result row for every backend.

## 11. Out of scope

- **Faithfulness judging on the wiki** - a second `eval-wiki-judge` test running `/ask` plus
  the LLM judge over the same questions. Deferred: slow on this box and the local judge is
  noisy.
- **Regression gate** - asserting the numbers do not drop. Deferred until a first baseline
  exists; gating against an unknown baseline is meaningless.
- **Section B graph-advantage questions** - blocked on hardware (`LEARNINGS.md` §14: entity
  extraction needs a GPU-resident small model this box does not have).
- Any change to `RetrievalEvalTest` or `FaithfulnessEvalTest` beyond the non-breaking
  `GoldenSet` overload.

## 12. Follow-up documentation

After the first successful run:

- `docs/LEARNINGS.md` - record the measured numbers and whether graph ever differed from
  hybrid on the full set.
- `docs/RAG-MASTERY.md` - update §3 (eval status) and scorecard row 3.
- `README.md` - add the run command and its prerequisites (stack up, wiki imported).
- `docs/implementation-notes.md` - decisions and deviations, per the standing rule.
