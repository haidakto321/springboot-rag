# Query routing and the cheapest correct path - design

Date: 2026-08-08
Status: approved, not yet planned
Builds on: `docs/superpowers/specs/2026-08-06-query-understanding-design.md` (implemented 2026-08-07,
commits `c0bd35c`, `0634f87`, `c0eadf7`)

## Purpose

Every question entering this system takes the same path: extract a filter with an LLM, retrieve,
generate. That path costs a measured **p50 of 49 s for extraction alone** and, for a whole class of
questions, is the wrong shape of answer. "hi" pays 49 s to find nothing. "How many overdue invoices
does ACME have?" gets ten chunks and a model guessing a number, when one `COUNT(*)` knows it
exactly.

This design routes the question first and sends each route down the cheapest path that can answer
it correctly.

**Scorecard**: `RAG-MASTERY.md` row 4 (query understanding and routing) is stuck at 2 for exactly
one stated reason - *"routing still does not exist"* - and row 8 (latency budget) is a 1 because the
one lever that matters, not calling the big model, has never been pulled. This move is aimed at
both.

## Scope

**In:** a three-way router on the answer path, an aggregate route that answers counts from SQL, a
chit-chat route that answers without any model call, route visibility in the trace/NDJSON/UI, two
latency experiments on the extraction call, and routing folded into the existing records eval as a
gated metric.

**Out:** SUM / AVG / GROUP BY aggregates. Multi-query fan-out, decomposition, HyDE. Pulling a
smaller model. Any records CRUD UI. Semantic caching.

**Unchanged:** the SEARCH route is today's path byte for byte, including widen-on-empty and an
explicit caller-supplied filter skipping extraction. `/search` and `/compare` stay unrouted - they
are the retrieval laboratory, and routing belongs on the answer path.

---

## 1. The three routes

```
question
  |
  v
QueryRouter  (deterministic rules, then one LLM call at temperature 0 with a fixed seed)
  |
  |-- CHITCHAT   -> canned reply. no extraction, no retrieval, no generation.
  |-- AGGREGATE  -> extract filter -> SQL COUNT(DISTINCT doc_id) -> fixed template.
  +-- SEARCH     -> today's path, unchanged.
```

| Route | Example | LLM calls | Expected cost |
|---|---|---|---|
| CHITCHAT | "hi" (rule) / "what can you do" (model) | 0 or 1 (the router) | rule: instant; model: router-bound |
| AGGREGATE | "how many overdue invoices for ACME" | 2 (router + extraction) | extraction-bound |
| SEARCH | "what does the late payment clause say" | 2 + generation | one router call more than today |

### Rules decide only CHITCHAT

The router short-circuits before any model call on blank input and on a small greeting/meta
pattern. It does **not** have a rule for AGGREGATE, and this is a deliberate departure from the
obvious design.

A `how many` keyword cannot separate *"how many invoices do we have"* (aggregate) from *"how many
days do I have to pay an invoice"* (a document question whose answer is a payment-terms clause).
The first is a count, the second is retrieval, and the keyword is identical. A misroute produces
the wrong *shape* of answer, not a slightly worse one, so the fuzzy half stays with the model. The
free half - an empty string, "hi", "thanks" - stays free.

This follows the rule already written in `RAG-MASTERY.md` section 5: if a rule is clear, do not
spend an LLM call on it. The point is that here the rule is only clear for one of the three.

### Router contract

```java
public record Decision(Route route, long latencyMs, String source) {}   // source: rule | model | fallback
Decision route(SearchContext ctx, List<Long> projectIds, String question);
```

Two properties are copied verbatim from `QueryUnderstanding`, because they are what made it safe:

1. **It never throws.** Any exception returns `SEARCH` with `source=fallback`. An answer that would
   have worked must not fail because routing did.
2. **Its output is validated, not trusted.** The reply is matched against the enum after trimming
   and lower-casing; anything else is `SEARCH`.

Failing open into SEARCH means every router failure degrades to *exactly today's behaviour*, which
is the only fallback that cannot make the system worse than it is now.

### The router must not cost what it saves

A router that adds 40 s to every question is a latency regression wearing a routing costume. The
current non-streaming path hardcodes `think: true`, and on qwen3:4b the reasoning tokens *are* the
latency. So the router call is configured against its own job:

- **`think: false`** - the output is one word from a closed set. There is nothing worth reasoning
  about, and the known `think:false` hazard (tag-less chain-of-thought landing in `content`) is
  harmless here precisely because the parse is a keyword scan, not a JSON parse.
- **`numPredict` capped small** - a bounded output for a bounded answer.
- **Tolerant parse**: scan the content for the first route keyword; anything unrecognised is SEARCH.

This needs two nullable fields on `ChatProvider.Options` - `think` and `numPredict` - forwarded to
Ollama only when set, so every existing call site keeps today's behaviour untouched. Router cost is
measured and reported per question in the eval; if it does not come in well under the extraction
call it replaces, the router is not worth its own latency and that gets written down.

The router runs at **temperature 0 with a fixed seed**, for the reason recorded on 2026-08-07: the
regression gate caught two identical extraction runs differing by 0.13 condition recall, and
sampling noise in a structured decision is a product defect, not test flakiness. Classification has
a right answer; do not sample it.

---

## 2. The aggregate route

```sql
SELECT COUNT(DISTINCT doc_id) FROM chunks
 WHERE <access-label clause> <project clause> <FilterSql fragment>
```

`RecordCountRepository` reuses `DocFilter.groupClause` and `FilterSql.render` - the same code the
six retrieval backends use. Not a second copy: an access-control clause that exists twice is an
access-control clause that will diverge once. `DISTINCT doc_id` is what makes this count *records*
rather than chunks, since one record renders to several chunks.

The answer is a fixed template - `"7 invoice records match values.customer = ACME Corp."` - built
in code. **No LLM writes the number.** A model asked to count from retrieved context is guessing
from a sample; a model asked to restate a number it was handed is an unnecessary way to introduce a
typo into a fact.

**Aggregate never widens.** Widen-on-empty is correct for retrieval, where zero hits means the
filter probably hid the answer. For a count, zero is frequently the true answer, and widening would
replace a correct 0 with a confidently wrong number. The mitigation for a wrong filter is instead
that the template prints the filter it applied, so "0 invoice records match values.customer = ACEM
Corp" shows the typo rather than hiding it.

An empty extracted filter is legitimate here: "how many documents are there" counts everything in
the caller's scope.

---

## 3. Latency

The 49 s p50 is decode time for qwen3:4b's reasoning tokens - the §6 trace measured generation at
97% of wall clock at roughly 10 tok/s, and extraction is the same model doing the same thing. Three
levers, ordered by certainty:

1. **Skip extraction on CHITCHAT.** Certain, no measurement needed: the call is simply not made.
2. **Cap extraction output.** Reuses the nullable `numPredict` added for the router. Bounds runaway
   reasoning. Risk is a truncated JSON body losing conditions, so it is kept only if
   `baseline-records.yaml` holds.
3. **Experiment: `think:false` for extraction only.** Reasoning tokens *are* the cost, so removing
   them is the largest available win. The known hazard is recorded in `LEARNINGS.md` §12: qwen3
   with `think:false` does not stop reasoning, it dumps tag-less chain-of-thought into `content`.
   Viable only if the JSON parse tolerates leading prose. Measured, and reverted if the gate moves.

Levers 2 and 3 are experiments with a stated revert condition, not decisions made in advance. The
habit is the point: **flag, measure, keep or delete.**

### The deferred lever

`app.route.model` and `app.understand.model` both stay pointed at `app.chat.model`. Pointing them at
a 1.7b model is the textbook model-tiering move and is **the intended path in the real project**;
it is deferred here only because this box has one chat model pulled and the experiment would
measure a download as much as a design. The knobs exist so that change is configuration, not code.

---

## 4. Wiring and visibility

- `QueryRouter` is called in `AskService` and `ChatService`, before extraction.
- `rag_trace` gains `route` and `route_latency_ms`; the stage-latency map gains a `route` entry.
- `/chat/stream` emits a `route` frame **before** the `filter` frame, so both arrive ahead of the
  first token.
- `app.js` renders a chip line above each answer showing route and applied filter. This also fixes
  the `filter` frame, which has been emitted since 2026-08-07 and silently dropped by the frontend -
  the streaming client currently handles `reasoning`, `token`, `sources`, `trace`, `guard`, `error`
  and nothing else.
- Config: `app.route.enabled` (default true) and `app.route.model` (default empty = the chat model).

---

## 5. Failure modes

| Failure | Behaviour |
|---|---|
| `app.route.enabled=false` | every question routes SEARCH; system behaves exactly as today |
| Router model down, times out, or returns junk | SEARCH, `source=fallback`, one warn line |
| Aggregate SQL throws | fall back to the SEARCH path and answer normally; never a 500 |
| Aggregate filter is wrong | count is printed next to the filter that produced it |
| Chit-chat | fixed text, no knowledge claims, no citations, no retrieval |
| No records in scope | facets empty, extraction already returns none; count covers the scope |

Security note: nothing new reaches SQL. Paths are validated against the facet catalogue before
`FilterSql` sees them, values are bound parameters, and the count carries the caller's access
labels in the same clause the retrieval path uses.

---

## 6. Eval and gate

`records-golden.yaml` gains `expectedRoute` on all 15 existing entries (all `search`) and 6 new
entries:

| Question | Route | Also scored |
|---|---|---|
| "hi" | chitchat | - |
| "what can you do" | chitchat | - |
| "how many invoices for ACME Corp" | aggregate | filter + count |
| "how many overdue invoices" | aggregate | filter + count |
| "how many delivery notes are there" | aggregate | filter + count |
| "how many contracts with Initech" | aggregate | filter + count |

**Ground-truth counts are computed, never written down.** `RecordGroundTruth` already resolves
expected filters against `RecordCorpus.generate(42)`; the expected count is that set's size. A
hardcoded number in YAML silently rots the day the generator changes, and a golden file that lies is
worse than no golden file.

`RecordEvalBaseline` gains `routing: {routeAccuracy, aggregateCountCorrect}` plus the per-question
route. Both are compared with **no tolerance**, the treatment over-extraction already gets: a
misroute is a wrong answer, not a fuzzy score that can drift 0.05 unnoticed.

The eval prints, per question, the route, its source, route latency and extraction latency, and p50s
at the end - the same reason the last run started printing per-question lines, that a silent
40-minute eval is indistinguishable from a hung one.

**Cost, stated before starting.** The `questions:` list in `baseline-records.yaml` is the golden-set
fingerprint, so adding six questions invalidates the committed baseline. Budget **3-5 live runs at
~30 min**: one to regenerate, one to verify the gate is stable across identical runs, one per
latency experiment.

---

## 7. Testing

**Offline unit:**
- `QueryRouterTest` against a stub `ChatProvider` - label parsing including casing and surrounding
  whitespace, unknown label to SEARCH, thrown exception to SEARCH, disabled flag, greeting rules,
  blank input.
- A router prompt-layout pin test, mirroring `QueryUnderstandingPromptTest`. The 0.07-recall bug was
  a prompt layout the tests never looked at.
- `AggregateAnswererTest` - 0 / 1 / n wording, and that the applied filter appears in the text.
- Routing comparison cases in `RecordEvalComparisonTest`, including a misroute failing the gate.

**Testcontainers:**
- `RecordCountRepositoryTest` - the access-label clause excludes chunks the caller cannot read from
  the count, project scope honoured, metadata filter applied, and `DISTINCT doc_id` counting records
  rather than chunks.

**Integration:**
- Aggregate answer end to end through `AskService`.
- `/chat/stream` emits `route` before `filter`, and both before the first token.
- `rag_trace` route columns populated on all three routes.

**Live:** the records eval runs described in section 6.

---

## 8. What would make this a failure

Written before the numbers exist, the way the row 4 scoring rule was:

- Route accuracy below **0.90** on 21 questions - the router is guessing, and a guess that reshapes
  the answer is worse than no router.
- Any regression in `baseline-records.yaml` extraction metrics that is caused by routing rather than
  by an experiment being deliberately kept.
- Aggregate counts that do not match ground truth exactly. A count is not a fuzzy metric.

If lever 2 and lever 3 both fail their gate, the latency half of this move produces one honest
sentence - *"reasoning tokens could not be cut on this model without losing filter quality"* - and
that gets written down rather than quietly dropped.
