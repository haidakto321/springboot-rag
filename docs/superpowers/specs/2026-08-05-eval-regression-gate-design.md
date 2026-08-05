# Design: retrieval eval regression gate (drill C)

Date: 2026-08-05
Status: approved, not yet implemented
Related: `docs/RAG-MASTERY.md` section 3 drill C and section 9 row 3,
`docs/superpowers/specs/2026-07-28-wiki-eval-harness-design.md`

## 1. Problem

`WikiRetrievalEvalTest` and `RetrievalEvalTest` **print** metrics. Nothing fails when the numbers
drop. Every retrieval change is therefore judged by whoever happens to be reading the console
output at the time, which is the definition of quality drifting down silently. `RAG-MASTERY.md`
section 9 row 3 scores 1 out of 3 for exactly this reason and cannot be raised until a gate exists.

This is not hypothetical. On 2026-08-05 the DJL reranker fix moved graph and rerank MRR from 0.919
to 0.909, because one question fell out of the top 10. That was caught only because someone was
watching the output during the run. The same regression landing in a commit next month produces a
green build.

## 2. Scope: gate the wiki eval only

`RetrievalEvalTest` ingests `docs/` as its corpus (`RetrievalEvalTest:89`). The corpus is therefore
the project's own documentation, which changes whenever anyone writes docs. Four files in `docs/`
were edited on 2026-08-05 alone. Baselining that eval produces a gate that fires on documentation
edits rather than on retrieval changes, so its failures would be ignored within a week.

`WikiRetrievalEvalTest` runs against the frozen, already-imported `docmaster` corpus (428 docs,
7,536 chunks). It reproduced its numbers exactly on a same-day re-run. Any movement there is
attributable to a code change.

**Decision: gate `WikiRetrievalEvalTest` only.**

Consequence, stated plainly: the gate runs only where the private corpus exists, which today means
one developer's machine. It is a pre-merge discipline tool, not an enforcement mechanism, and it
can never gate CI. Making the self-corpus eval gateable requires freezing a dedicated test corpus
and re-pointing all 18 questions in `golden.yaml`. That is a larger project and is explicitly out
of scope here.

## 3. What the gate asserts

Two independent checks per backend. Both must pass.

**Check 1: aggregate floors.** For each backend, `recall@5`, `MRR`, and `hit@1` must each be at or
above `baseline - tolerance`.

**Check 2: no new misses.** A question that the baseline records as found (rank > 0) for a given
backend must still be found. Going from any rank to rank 0 fails, regardless of what the aggregate
metrics did.

Improvement never fails. A metric above baseline passes, and a question that was missed in the
baseline but is found now passes. The gate is a floor, not a pin. Improvements are picked up the
next time the baseline is regenerated.

Check 2 exists because check 1 alone would have missed the real 2026-08-05 regression. When the
cross-encoder pushed question 11 out of the top 10, `recall@5` stayed flat at 0.909 and `hit@1`
stayed flat at 0.909, because that question had been at rank 9 and was never inside either window.
Only MRR moved, and only by 0.010. An aggregate tolerance wide enough to be comfortable would have
hidden it entirely.

## 4. Tolerance: 0.02

With 11 questions the metrics are quantized. `recall@5` and `hit@1` move in steps of 1/11 = 0.091,
so any tolerance below 0.091 makes those two effectively exact-or-better. Tolerance only tunes MRR
in practice.

MRR scale for reference:
- a question slipping rank 1 to rank 2 costs 0.045
- a question slipping rank 9 to rank 10 costs 0.001
- a question at rank 1 disappearing entirely costs 0.091

0.02 therefore absorbs deep-list shuffling and fails any degradation near the top of the list. It
is a single constant applied to all three metrics and all backends; per-metric or per-backend
tolerances are not worth the configuration surface at this size.

Measured noise is currently zero: the same command produced identical numbers twice on 2026-08-05.
0.02 is headroom against nondeterminism that has not appeared yet, not against observed variance.

## 5. Baseline storage

`src/test/resources/eval/baseline-wiki.yaml`, committed to the repo, one section per reranker
variant. The example below is abridged: the real file lists every backend and every question in
full, and is generated rather than hand-written (section 6).

```yaml
corpus:
  projectId: 5
  projectName: docmaster
  docCount: 428
  chunkCount: 7536
questions:
  - "Which two electronic-invoice formats are used for Germany?"
  - "From when is e-invoicing mandatory in Germany?"
  # ...all 11, in golden-wiki.yaml order
identity:
  metrics:
    fts:      {recall5: 0.182, mrr: 0.182, hit1: 0.182}
    pgvector: {recall5: 0.909, mrr: 0.919, hit1: 0.909}
    qdrant:   {recall5: 0.909, mrr: 0.919, hit1: 0.909}
    hybrid:   {recall5: 0.909, mrr: 0.919, hit1: 0.909}
    rerank:   {recall5: 0.909, mrr: 0.919, hit1: 0.909}
    graph:    {recall5: 0.909, mrr: 0.919, hit1: 0.909}
  found:
    fts:
      - "From when is e-invoicing mandatory in Germany?"
      - "Who is responsible for updating the list of extraordinary access grants?"
    pgvector: [ ...all 11 question strings... ]
    # etc
djl:
  metrics:
    rerank:   {recall5: 0.909, mrr: 0.909, hit1: 0.909}
    graph:    {recall5: 0.909, mrr: 0.909, hit1: 0.909}
    # fts, pgvector, qdrant, hybrid unchanged from identity but recorded explicitly
  found:
    rerank: [ ...10 question strings, question 11 absent... ]
```

**Questions are keyed by their full text, not by index.** Index keys break the moment
`golden-wiki.yaml` is reordered or extended, and would silently re-point assertions at the wrong
question. Full text is verbose but unambiguous and reads well in a diff.

**A question present in the run but absent from the baseline is reported as new and is not gated.**
Adding a golden question must not fail the build. It shows up in the output as a notice, and gets
picked up the next time the baseline is regenerated.

**The top-level `questions` list records the whole golden set, not only the found ones.** Without
it, a question missing from a backend's `found` list is ambiguous: it could be newly added, or it
could have existed all along and been missed. That ambiguity does not affect pass or fail, since
check 2 only fires on found-to-missed, but it does make the "new question" notice impossible to
compute. The list is shared by both variants because the golden set is.

**Two variants because both are legitimate.** `-Deval.rerank=djl` changes the expected numbers by
design, so it needs its own baseline rather than being treated as a regression against the identity
numbers.

**The `corpus` block is a staleness fingerprint, shared by both variants.** Re-importing the wiki
shifts chunk boundaries and ids, so ranks move for reasons that are not regressions. Without the
fingerprint that surfaces as six simultaneous backend failures, which reads exactly like a real
regression and costs an investigation to disprove. `ProjectSummary` already carries `id`, `name`,
`docCount`, and `chunkCount`, and `requireCorpus()` already resolves it, so the data is on hand at
no extra query cost. This is a staleness check, not a security or integrity check: it catches the
honest mistake of forgetting to re-baseline, and makes no attempt to detect a corpus edited in place
that happens to preserve both counts.

**The variant key is derived from the actual `Reranker` bean's simple class name, not from the
`-Deval.rerank` flag** (`IdentityReranker` to `identity`, `DjlReranker` to `djl`). The test already
injects `Reranker` purely to print its class name, so the information is on hand. Deriving from the
bean means setting `app.rerank.provider=djl` in `application.yml` cannot mislabel a baseline
section, which deriving from the flag would allow.

## 6. Regenerating the baseline

`-Deval.baseline.update=true` rewrites the current variant's section of `baseline-wiki.yaml` from
the run, prints a clear notice, and **skips both assertions for that run**. Other variants' sections
are preserved untouched.

Rationale: hand-transcribing 6 backends times 3 metrics plus 11 question outcomes, twice over, is
error-prone, and a transcription error silently becomes the new bar. Regeneration removes the
transcription step while keeping the review step, because accepting a new baseline is still a
committed file diff that a human reads. "We lowered the bar" cannot happen invisibly.

The flag must refuse to write when the corpus preconditions did not pass, so an absent corpus can
never blank the baseline.

## 7. Components

| Unit | Responsibility | Depends on |
|---|---|---|
| `CorpusFingerprint` | Immutable record: project id, project name, doc count, chunk count | nothing |
| `BackendMetrics` | Immutable record: recall@5, MRR, hit@1, plus the single `of(ranks, questionCount)` that computes them | nothing |
| `EvalBaseline` | Immutable record: corpus fingerprint, variant, full question list, per-backend metrics, per-backend found-question sets | `CorpusFingerprint`, `BackendMetrics` |
| `EvalBaselineStore` | Load `baseline-wiki.yaml` into `EvalBaseline`; write one variant's section back, preserving the others | SnakeYAML, filesystem |
| `BaselineComparison` | Pure function `(EvalBaseline expected, EvalBaseline actual, double tolerance) -> List<Violation>` | nothing |
| `WikiRetrievalEvalTest` | Orchestration: sweep, print the three existing reports, then compare and assert | Spring, live stack |

`BaselineComparison` being pure is the point of this split. The gate's actual decision logic becomes
unit-testable with fabricated numbers, offline, in milliseconds, with no corpus, no Docker, and no
Ollama. Nothing in the `eval` package can be tested that way today.

`Violation` carries backend, what failed, expected, actual, and for a new miss the question text, so
the failure message names the question rather than only a number.

## 8. Data flow

```
requireCorpus()            skip if project or stack absent  (unchanged)
  -> runAll()              6 backends x 11 questions        (unchanged, one sweep)
  -> printAggregate()      existing report                  (unchanged)
  -> printMatrix()         existing report                  (unchanged)
  -> printGraphVsHybrid()  existing report                  (unchanged)
  -> variant = reranker bean class -> "identity" | "djl"
  -> actual  = EvalBaseline built from the BackendRun list
  -> if -Deval.baseline.update=true: store.write(variant, actual); print notice; return
  -> expected = store.load(variant)
  -> if expected.corpus() != actual.corpus(): fail with the stale-baseline message, compare nothing
  -> violations = BaselineComparison.compare(expected, actual, 0.02)
  -> assert violations is empty, message lists every violation
```

The gate consumes the same `BackendRun` list the reports already use. One Spring context, one
sweep, one set of embedding calls. No second test class, because a second class would double the
expensive part for two tests that require byte-identical inputs.

## 9. Error handling

- **Corpus or stack absent:** unchanged. `requireCorpus()` skips via `Assumptions`, and the gate is
  never reached. A fresh clone still passes.
- **Corpus fingerprint mismatch:** hard failure **before** any metric comparison, so a re-imported
  corpus reports one clear cause instead of six fake backend regressions. The message states both
  fingerprints and the remedy, for example:
  `corpus changed: 428 docs / 7536 chunks -> 430 docs / 7602 chunks; baseline is stale, regenerate
  with -Deval.baseline.update=true`.
- **Baseline file missing:** hard failure, not a skip, with a message naming
  `-Deval.baseline.update=true`. The file is committed, so absence means someone deleted it, and
  that should be loud. This check runs after the corpus precondition, so a fresh clone still skips
  rather than failing.
- **Variant section missing from an existing file:** same hard failure, same remedy.
- **Backend present in the run but absent from the baseline:** hard failure. Unlike a new question,
  a new backend means `BACKENDS` changed and the baseline is genuinely stale.
- **Backend present in the baseline but absent from the run:** hard failure. A backend disappearing
  from `BACKENDS` is exactly the kind of silent coverage loss this gate exists to catch, and it must
  not pass merely because there is nothing left to compare.
- **Multiple violations:** all are collected and reported together. The assertion does not stop at
  the first one, because a real regression usually shows up across several backends and seeing one
  at a time wastes a full eval run per iteration.

## 10. Testing

**`BaselineComparisonTest`** - plain unit test, no tag, runs in the normal build, offline:
- passes when actual equals baseline
- passes when actual is above baseline
- passes when actual is below baseline but within tolerance
- fails when actual is below baseline beyond tolerance, naming the backend and metric
- fails on a new miss even when all three aggregate metrics are within tolerance (the 2026-08-05
  case, encoded as a regression test)
- reports a question absent from the baseline as new without failing
- fails on a backend absent from the baseline
- fails on a backend present in the baseline but absent from the run
- fails on a corpus fingerprint mismatch, and reports only that, even when the metrics would also
  have produced violations

**`EvalBaselineStoreTest`** - round trip through a temp file: write, read back, and confirm that
writing one variant leaves the other variant's section byte-identical.

**Manual verification** - run the wiki eval unchanged and confirm it passes against the committed
baseline, both with and without `-Deval.rerank=djl`.

## 11. Non-goals

- Gating `FaithfulnessEvalTest`. Judge output is LLM-generated and needs its own noise study first.
- Gating `RetrievalEvalTest` or making it CI-runnable. See section 2.
- Freezing a dedicated test corpus.
- Trend history, dashboards, or metric storage over time.
- Per-metric or per-backend tolerances.
- Any change to retrieval behaviour. This work only observes.
- The deferred minors from the 2026-07-28 harness ledger, and the dead `app.rerank.maxLength`
  property.

## 12. Risks

- **Re-importing the wiki invalidates the baseline.** Chunk ids and ordering change, so the numbers
  move for reasons that are not regressions. **Mitigated** by the corpus fingerprint (section 5):
  the gate reports one stale-baseline failure naming the remedy, instead of six fake backend
  regressions that read like a real defect. Residual risk: a corpus edited in place that preserves
  both counts still slips through as an apparent regression. Judged acceptable, since re-imports
  change counts in practice. Worth a line in the README next to the run command.
- **11 questions is a coarse instrument.** One question is 9 percent of every metric. The gate can
  only catch regressions large enough to move a question, and it will never detect a subtle
  reordering that leaves ranks intact. Move 2 (human labels) is the path to a finer instrument.
- **Single-machine enforcement.** Nothing forces the gate to run. It is discipline, backed by a
  README instruction, not automation.
- **Tolerance is calibrated against zero observed noise.** If genuine nondeterminism appears later,
  the first symptom is a confusing red build rather than a gradual widening. Accepted knowingly.

## 13. Constraints inherited from the existing harness

- `WikiRetrievalEvalTest` stays read-only by construction. The gate adds no new injected bean beyond
  what is already present, and never writes to the live database.
- `spring.sql.init.mode=never` stays.
- No new dependencies. SnakeYAML is already on the test classpath via the existing `GoldenSet`.
- Use `./mvnw`, not `mvn`.
- Never use the em-dash character in any file.
