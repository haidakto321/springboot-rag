# Query understanding for record search - design

Date: 2026-08-06
Status: approved, not yet planned
Builds on: `docs/superpowers/specs/2026-08-06-record-search-design.md` (implemented same day)

## Purpose

Record search now supports structured filtering across all six backends, but a filter can only be
supplied as hand-written JSON. A person asking *"unpaid ACME invoices from Q2 that mention late
payment"* gets an unfiltered semantic search, because nothing turns that sentence into
`docType=invoice` + `values.customer=ACME` + a date range. This closes that gap: the chatbot uses
the filtering that already exists.

It also answers the objection that made the previous work unmeasurable. There is no eval corpus for
records, so any claim about filtering helping retrieval is currently unfalsifiable. This design
ships the corpus and the golden set alongside the feature, and reports numbers with and without
extraction.

**Scorecard**: this is `RAG-MASTERY.md` section 4 (query understanding and routing), currently 1.

## Scope

**In:** a facet catalogue derived from indexed metadata, an LLM extraction step validated against
that catalogue, auto-widening when an extracted filter matches nothing, trace visibility, and a
committed synthetic record corpus with a golden set.

**Out:** aggregation questions ("how many invoices"), sorting, multi-hop reasoning, routing between
retrieval backends, and any UI. Also out: making the eval a blocking gate - it reports first, the
same order drill C followed for the wiki eval.

**Unchanged:** an explicit caller-supplied filter keeps working exactly as today and skips
extraction entirely.

---

## 1. Facet catalogue

The extractor cannot be trusted to invent paths, and it cannot be told the schema up front because
the set of document types is open. So the catalogue is **derived from what is actually indexed**.

```
GET /projects/{projectId}/facets            -> every docType
GET /projects/{projectId}/facets?docType=invoice
```

```json
{
  "docTypes": ["invoice", "delivery-note"],
  "facets": [
    {"docType":"invoice","path":"values.customer","type":"text",
     "samples":["ACME Corp","GLOBEX Ltd","Initech"],"distinctCount":37},
    {"docType":"invoice","path":"values.issueDate","type":"date",
     "samples":["2026-01-14","2026-05-02"],"distinctCount":180},
    {"docType":"invoice","path":"values.total","type":"number",
     "samples":["199.0","1899.5"],"distinctCount":174}
  ]
}
```

**Derivation.** One SQL query over `chunks.metadata`, restricted to the caller's access labels like
every other read: walk the `values` subtree with `jsonb_each`, emit leaf paths, sample up to
`app.understand.facet-samples` (default 5) distinct values per path, and count distinct values.
Paths under `prov` are excluded - provenance is filterable but nobody asks a question about a bbox -
except `conf.min`, which is offered as a numeric facet because "only trustworthy results" is a real
request.

**Type inference** is by value shape across the samples: all parse as numbers -> `number`; all match
`YYYY-MM-DD` -> `date`; otherwise `text`. Inference only picks the cast the filter DSL will use, so
a wrong guess degrades to a text comparison rather than an error.

**Caching.** Held per `(projectId, groups)` for `app.understand.facet-ttl` (default 5 minutes),
because it runs once per question and the corpus changes far more slowly than it is queried. The
cache key includes the groups so the catalogue can never leak the existence of a facet the caller
cannot read.

## 2. The extraction step

`QueryUnderstanding.extract(SearchContext, projectIds, question)` -> `Extraction`.

```java
public record Extraction(MetadataFilter filter, String rawModelOutput, long latencyMs,
                         List<String> droppedReasons) {}
```

One LLM call. The prompt carries the question, the docType list, and the facet lines
(`path | type | sample values`), and demands strict JSON:

```json
{"docType": "invoice",
 "filters": [{"path":"values.customer","op":"eq","value":"ACME Corp"},
             {"path":"values.issueDate","op":"range","gte":"2026-04-01","lt":"2026-07-01","type":"date"}]}
```

**Model.** `app.understand.model`, defaulting to `app.chat.model`. A separate knob on purpose: a
small fast model doing extraction while the large one answers is the model-tiering lever
`RAG-MASTERY.md` section 8 names, and this is the first place with a before/after to measure it.
`think:true` is sent as everywhere else in this codebase - qwen3 leaks tag-less reasoning into
content otherwise (`LEARNINGS.md` section 12).

**Validation, not trust.** Model output is parsed then filtered against the catalogue:

| Problem | Action |
|---|---|
| Path not in the catalogue | drop that condition, record a reason |
| docType not in the catalogue | drop the docType, keep the conditions |
| Unknown op, malformed range, empty `in` | drop that condition (`MetadataFilter.parse` rules) |
| More than `app.understand.max-conditions` (default 4) | keep the first N, record a reason |
| Value longer than 200 chars | drop that condition |
| Nothing survives | empty filter - the same as not extracting |

What survives is built through the existing `MetadataFilter`, so extraction can never express
anything the DSL cannot, and can never reach the access-label term.

**Never fatal.** Model down, timeout, unparseable JSON, or an empty response all mean "no filter".
An answer that would have worked must not fail because query understanding did.

## 3. Retrieval flow

Applies to `/ask` and `/chat/stream` only. `/search` and `/compare` stay untouched: they are the
backend-comparison surface, and an LLM call inside them would pollute exactly the timings the
project exists to compare.

```
question
  -> caller supplied a filter?  yes -> use it, skip extraction
                                no  -> QueryUnderstanding.extract
  -> retrieve with the filter
  -> zero hits AND the filter was non-empty?
        yes -> retrieve again unfiltered, mark widened
        no  -> done
  -> generate
```

**Why widening.** A mis-extracted value ("ACME" vs "ACME Corp") otherwise turns an answerable
question into a confident refusal, and the user cannot tell that from the document genuinely not
existing. Widening costs one extra retrieval - measured in single-digit milliseconds - and converts
a silent wrong answer into a visible, explained one. Widening happens only when the filtered result
is **empty**; a filter that returns few results is doing its job.

**Chat.** Retrieval runs on the condensed query, extraction on the **raw** question, because
condensation rewrites pronouns but can also drop the very entity the filter needs.

**Config.** `app.understand.enabled` (default true) turns the whole step off, restoring today's
behaviour exactly.

## 4. What the caller sees

- `AskResponse` gains `appliedFilter` and `widened`. `appliedFilter` is the `MetadataFilter` itself,
  serialised as a JSON object with the same shape the API accepts (`{"docType":...,"filters":[...]}`)
  and null when no filter was applied - so a client can echo it straight back as an explicit filter.
- `/chat/stream` gains a `filter` NDJSON frame: `{"type":"filter","applied":{...},"widened":true}`,
  emitted before the first token so a UI can show "searching invoices for ACME Corp" while the
  answer streams.
- `rag_trace` gains `applied_filter JSONB` and `filter_widened BOOLEAN`, and `stage_latency_ms`
  gains an `understand` entry. Without those three, "why did it answer that?" is unanswerable for
  exactly the requests where the answer surprised someone.

## 5. Components

| Unit | Responsibility | Depends on |
|---|---|---|
| `FacetRepository` | the SQL that derives paths, types, samples from `chunks.metadata` | JdbcTemplate |
| `FacetCatalogue` | caching + type inference on top of it | `FacetRepository` |
| `FacetController` | `GET /projects/{id}/facets` | `FacetCatalogue` |
| `QueryUnderstanding` | prompt, one LLM call, parse | `ChatProvider`, `FacetCatalogue` |
| `ExtractionValidator` | model output -> validated `MetadataFilter`; pure | `MetadataFilter` |
| `AskService` / `ChatService` | extract, retrieve, widen, report | the above |

`ExtractionValidator` is pure and is where the subtle rules live, so the dropping behaviour is
unit-testable without a model or a database.

## 6. Error handling

- Ollama unreachable or slow: caught, logged once per request, empty filter, answer proceeds.
- Model returns prose around the JSON: first balanced `{...}` block is parsed; failing that, empty
  filter.
- Model invents a path or a docType: dropped by the validator, reason recorded in the trace.
- Facet query fails: empty catalogue, therefore empty filter, answer proceeds. A broken catalogue
  must degrade to today's behaviour, not to an error.
- Caller filter plus extraction both present: caller wins entirely. Merging two filters would let a
  model silently narrow a scope the caller deliberately set.

## 7. The eval corpus

**Generator.** `RecordCorpus.generate(seed)` produces ~200 records across three document types with
deliberately different schemas - invoice (customer, issueDate, status, total, lineItems[]),
delivery-note (carrier, deliveredOn, packages[]), contract (party, effectiveDate, termMonths,
value). Fixed seed, so the corpus is identical on every machine and in CI. Values come from small
fixed vocabularies, which is what makes expected results computable rather than eyeballed.

**Golden set.** `src/test/resources/eval/records-golden.yaml`, ~15 questions, each carrying the
question, the expected filter conditions, and the expected document ids. Coverage deliberately
includes:

- single equality (`"invoices for ACME Corp"`)
- date range (`"invoices from Q2"`)
- numeric range (`"invoices over 1000"`)
- in-list (`"open or overdue invoices"`)
- docType only (`"what delivery notes do we have from Speedy Freight"`)
- cross-type wording that must NOT produce a docType (`"anything mentioning late payment"`)
- **an ambiguous question where the correct extraction is no filter at all**
- **a mistyped customer name, where the correct behaviour is widening**

The last two matter most: a metric that only rewards extracting filters trains the design toward
over-extraction, which is the failure mode that hides answers.

**`RecordFilterEvalTest`** (tag `eval-records`, added to the pom `excludedGroups` like the others)
runs on Testcontainers - so unlike `WikiRetrievalEvalTest` it works on a fresh clone and in CI,
which also closes the frozen-corpus item `ROADMAP.md` has been carrying. It skips, rather than
fails, when no chat model is reachable.

**Reported metrics**

| Metric | Why |
|---|---|
| condition precision / recall vs the expected filter | did it extract the right thing |
| recall@5 and MRR **with** extraction | end-to-end quality |
| recall@5 and MRR **without** extraction | the honest baseline - the whole point |
| widen rate | how often extraction was wrong enough to need rescuing |
| extraction latency p50 | what the second model call costs |

A report, not a gate. It becomes a gate once the numbers are stable, exactly as the wiki eval did.

## 8. Testing

**Unit (no container, no model):**
- validator: unknown path dropped; unknown docType dropped but conditions kept; over-limit
  conditions truncated; oversized value dropped; malformed range dropped; everything dropped -> empty
  filter equals "no filter", never a predicate matching nothing.
- prompt builder: facets rendered, question included, catalogue with zero facets still produces a
  valid prompt.
- JSON extraction from a response wrapped in prose.
- type inference: numbers, dates, mixed values falling back to text.

**Integration (Testcontainers):**
- facets endpoint returns paths that exist and omits ones the caller cannot read.
- extraction disabled by config leaves behaviour byte-identical to today.
- a stub ChatProvider returning a known filter narrows `/ask` results.
- a stub returning a filter matching nothing triggers widening, and the response reports
  `widened: true` with the filter that was dropped.
- a stub that throws leaves the answer working with no filter.
- caller-supplied filter beats extraction and no extraction call is made.
- `rag_trace` row carries `applied_filter`, `filter_widened`, and an `understand` stage.

**Live evidence for `LEARNINGS.md`:** the eval table with and without extraction, plus the
extraction latency measured against `app.chat.model` versus a smaller model in
`app.understand.model` - the first real number for the section 8 model-tiering lever.

## 9. Documentation to update when built

`docs/implementation-notes.md` (decisions and deviations, per the standing rule), `docs/LEARNINGS.md`
(new section: query understanding, over-extraction, and why widening beats a confident refusal),
`docs/ARCHITECTURE.md` (the extraction step in the `/ask` and `/chat/stream` paths),
`docs/RAG-MASTERY.md` (section 9 row 4 re-score, honestly), `docs/ROADMAP.md` (frozen-corpus item),
`README.md` (facets endpoint, `app.understand.*` config).
