# AI search over extracted records - design

Date: 2026-08-06
Status: approved, not yet planned
Supersedes for now: RAG-MASTERY section 7 wiki re-sync (see "Why not wiki re-sync")

## Purpose

The real project this sandbox prepares for already owns upload -> parse -> schema extraction.
What it hands the search layer is **a JSON record plus metadata, stored in Postgres** - not a
markdown file. Three facts shape everything below:

1. **Document types differ, and the set is open.** Some tenants preconfigure a schema for a
   handful of types. Others upload types nobody has seen, with no fixed schema; a separate paid
   "suggest schema" flow may produce one later.
2. **Records are nested and contain arrays** - line items, parties, sections.
3. **The extraction output is noisy.** Fields arrive wrapped with provenance: a confidence score
   and grounding data (page, bounding box, character spans). That noise must never reach the
   embedded text, and the grounding is worth keeping because it upgrades a citation from "this
   document" to "this box on page 2".

So the search layer must (a) index an arbitrary JSON record with zero configuration, (b) get
better when configuration exists, and (c) let users narrow by structured metadata, because real
questions are "invoices from Q2 for customer X that mention late payment", not "search
everything".

## Why not wiki re-sync

The previously specced work (hash-based re-sync of an Azure wiki git clone) was half
transferable. The registry core - content hash, skip-if-unchanged, delete propagation across
Postgres and Qdrant, embedding-model change forces re-index - is exactly what an upload product
needs. The wiki walker, git dates, folder-hierarchy edges, and prune-by-directory-origin are
throwaway for a product whose input is a JSON record. This design keeps the transferable half
(section 5) and drops the rest.

## Scope

**In:** record ingest API, generic JSON rendering, optional per-type render profiles, chunk-level
metadata, a filter DSL applied inside all six retrieval backends, record-level freshness.

**Out:** the parse/extraction pipeline (owned upstream), the suggest-schema service itself,
multi-tenant identity beyond the existing project + group model, async job queues, UI redesign
beyond exposing filters.

**Unchanged:** markdown upload (`POST /projects/{id}/documents`) keeps working exactly as today.
Records and markdown documents coexist in the same `chunks` table and the same retrieval path.

---

## 1. Ingest API

```
POST /projects/{projectId}/records
{
  "docId":    "INV-5575",
  "docType":  "invoice",
  "record":   { ...arbitrary extracted JSON... },
  "metadata": { "sourceFile": "INV-5575.pdf" },     // optional, merged into chunk metadata
  "groups":   ["finance"]                            // optional access label, existing semantics
}
-> 200 {"docId":"INV-5575","chunksStored":12,
        "status":"indexed|metadata-refreshed|skipped","warnings":[...]}

DELETE /projects/{projectId}/records/{docId}
```

`docType` is required and free-form: it is the profile lookup key and a filter field, never a
validated enum, because the type set is open.

`status` reports what the ingest actually did: `indexed` (embedded), `metadata-refreshed` (only
provenance changed, no embedding call), or `skipped` (nothing changed). See section 5.

Access labels, group validation, and the injection scan behave exactly as on the markdown path -
extracted text is still untrusted material.

## 2. Rendering a record into text

`RecordRenderer` turns one JSON record into an ordered list of `(text, breadcrumb)` blocks. It
never fails on unknown shapes.

**Generic rules (no configuration required):**

| Input | Becomes |
|---|---|
| top-level scalars | one **header** block, one `label: value` line each |
| nested object | one **section** block; deeper leaves flattened as `parent.child: value` |
| array of objects | **one block per element**, with the parent's scalar context prepended |
| array of scalars | a single `label: a, b, c` line inside its owning block |
| null / empty string | omitted (an empty line is noise in a vector) |

**Breadcrumb** is the JSON path of the block - `lineItems[3]`, `parties.buyer` - stored in the
existing `chunks.heading_path` column. Citations, the chunk viewer, the reranker, and the trace
view therefore need no change: they already render a breadcrumb string.

**Labels.** A path segment becomes a human label by splitting camelCase / snake_case
(`issueDate` -> "Issue date"). Cheap, and it measurably helps embeddings over raw keys.

**Size.** Blocks flow through the existing `IngestService.capToBudget`, so no chunk can exceed
the 2000-char embedding ceiling. A single array element larger than the cap splits at whitespace
and keeps its breadcrumb.

### 2.1 Value wrappers - stripping extraction noise

Extracted fields arrive wrapped:

```json
{"customer": {"value": "ACME Corp", "confidence": 0.82,
              "grounding": {"page": 2, "bbox": [12, 44, 90, 60]}}}
```

A generic flatten would embed `customer.confidence: 0.82` and `customer.bbox: 12,44,90,60`.
Coordinates and scores are the worst possible embedding input: they carry no meaning, they
dilute the vector, and digit strings can match other digit strings. So the renderer unwraps.

**Detection rule (`ValueWrapper.detect`).** An object is a value wrapper when **both** hold:

1. it has exactly one value-ish key: `value`, `text`, `content`, or `raw`
2. every remaining key is a known noise key: `confidence`, `score`, `grounding`, `bbox`,
   `boundingBox`, `polygon`, `page`, `pageNumber`, `spans`, `offsets`, `source`

If a wrapper carries an unrecognised key, it is **not** treated as a wrapper - the whole object
renders generically. Failing open here is deliberate: silently dropping an unknown key would
lose real extracted data, while an extra `label: value` line only costs a little noise.

**Result of unwrapping:**

| Part | Goes to |
|---|---|
| the value | embedded text, under the field's label |
| `confidence` / `score` | metadata as `<path>._confidence` (number) |
| `page` / `pageNumber` | metadata as `<path>._page` (number) |
| `bbox` / `polygon` / `boundingBox` | metadata as `<path>._bbox` (array) |
| `spans` / `offsets` / `source` | metadata as `<path>._span` |

Nested wrappers unwrap recursively, so `lineItems[3].sku.value` renders as a plain value with
`lineItems[].sku._confidence` alongside it.

**Profile override.** When a tenant's extractor names things differently, the profile declares
them and detection is skipped for that docType:

```json
{"wrapper": {"valueKeys": ["val"], "confidenceKeys": ["certainty"],
             "groundingKeys": ["locator"]}}
```

### 2.2 Confidence policy

**Every field is indexed regardless of its score.** Low confidence never removes text from the
index: a dropped field is a question that can never be answered, and nobody can tell from the
outside why the search missed. Instead confidence is made visible and filterable:

- per field: `<path>._confidence`
- per chunk: `_confidence.min` and `_confidence.avg` over the fields in that chunk

A caller who wants only trustworthy hits adds a filter
(`{"path":"_confidence.min","op":"range","gte":0.7,"type":"number"}`). A UI can flag a shaky
citation. The retrieval path itself stays neutral - a confidence threshold is a caller's policy,
not a property of the index.

Fields with no confidence reported get no `_confidence` key at all; they must not default to 0
(invisible to every threshold filter) or to 1.0 (a fabricated guarantee).

## 3. Render profiles (optional configuration)

A profile is **data, not code**: one row per (project, docType).

```sql
CREATE TABLE IF NOT EXISTS render_profile (
    project_id   BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    doc_type     VARCHAR(128) NOT NULL,
    body         JSONB NOT NULL,
    version      INT NOT NULL DEFAULT 1,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, doc_type)
);
```

`body` shape:

```json
{
  "include":   ["customer.name", "issueDate", "total", "lineItems[].description"],
  "exclude":   ["internal.*", "rawOcrText"],
  "labels":    { "issueDate": "Invoice date", "total": "Amount due" },
  "filterOnly":["internal.batchId"],
  "boundaries":["lineItems[]"],
  "wrapper":   { "valueKeys": ["val"], "confidenceKeys": ["certainty"],
                 "groundingKeys": ["locator"] }
}
```

Semantics, in order: `exclude` wins over `include`; an empty `include` means "everything not
excluded"; `filterOnly` paths land in metadata but never in embedded text; `boundaries` names the
paths that start a new chunk, overriding the generic array rule.

**No profile = generic rendering.** An unseen doc type is fully searchable the moment it lands.
Adding a profile later re-indexes only that docType, because `version` participates in the
freshness hash (section 5). This is the whole point: the open-schema case degrades to "good", not
to "broken", and the suggest-schema output has a place to land - an INSERT, not a deploy.

**Endpoints:** `PUT /projects/{id}/profiles/{docType}` (upsert, bumps `version`),
`GET /projects/{id}/profiles/{docType}`, `GET /projects/{id}/profiles`.

## 4. Metadata and filtering

### Storage

```sql
ALTER TABLE chunks ADD COLUMN IF NOT EXISTS doc_type VARCHAR(128);
ALTER TABLE chunks ADD COLUMN IF NOT EXISTS metadata JSONB NOT NULL DEFAULT '{}'::jsonb;
CREATE INDEX IF NOT EXISTS idx_chunks_metadata ON chunks USING gin (metadata jsonb_path_ops);
CREATE INDEX IF NOT EXISTS idx_chunks_doc_type ON chunks (project_id, doc_type);
```

Metadata is stored **per chunk**, denormalized from the record, so every filter is a predicate
inside the retrieval query with no join:

- record-level scalars, flattened to dotted paths: `{"customer.name":"ACME","issueDate":"2026-05-02","total":1899.5}`
- plus the caller's `metadata` object
- plus, for an array-element chunk, that element's own scalars (`lineItems[].sku`) - the rule
  that makes "the line item with SKU X" directly retrievable instead of requiring the whole
  invoice to win first
- plus the provenance keys stripped by the value unwrapper (section 2.1): `<path>._confidence`,
  `_page`, `_bbox`, `_span`, and the chunk-level `_confidence.min` / `_confidence.avg`

Provenance keys are prefixed with `_` so they can never collide with an extracted field name and
so a UI can tell "data" from "data about the data" without a schema.

**Grounding earns its place in retrieval, not just in storage.** With `_page` and `_bbox` on the
chunk, an answer citation can deep-link to the exact region of the source PDF, and a filter like
`{"path":"_page","op":"eq","value":3,"type":"number"}` narrows to one page. Both come free once
the unwrapper stops throwing that data away.

Qdrant payload mirrors the same flattened keys, so both vector stores filter identically.

### Filter DSL

Sent on `/search`, `/compare`, and in the `/chat/stream` body:

```json
{
  "docType": "invoice",
  "filters": [
    {"path": "customer.name", "op": "eq",    "value": "ACME"},
    {"path": "status",        "op": "in",    "values": ["open", "overdue"]},
    {"path": "issueDate",     "op": "range", "gte": "2026-04-01", "lt": "2026-07-01"},
    {"path": "approvedBy",    "op": "exists"}
  ]
}
```

Ops: `eq`, `in`, `range` (`gte`/`gt`/`lte`/`lt`), `exists`. Filters AND together. Values are
compared as text unless the filter carries `"type":"number"` or `"type":"date"`, which selects a
cast in Postgres and the matching Qdrant range type.

### Enforcement rules (the part that goes wrong quietly)

1. **Filters live inside every backend query**, never as a post-filter on results: Postgres
   `metadata @> ...` / `metadata->>'path'` predicates, Qdrant payload `Filter.must`. A post-filter
   returns fewer than topK hits and looks like "bad recall".
2. **Filters apply before the reranker over-fetch trims.** `app.rerank.candidates`=50 is fetched
   *already filtered*; filtering after the trim silently drops matching documents.
3. **Graph expansion inherits the filter.** An expanded neighbour that fails the filter is
   dropped, the same rule access labels already follow.
4. **Filters compose with access labels, never replace them.** `allowed_groups` stays a separate
   AND term. A filter is a user preference; a label is a boundary.
5. **An empty filter list is "no filter"** - it must not degenerate into a false predicate. This
   already bit the doc-id filter (LEARNINGS section 13: an empty Qdrant `should` matches nothing).

## 5. Freshness

Reuses the registry idea from the dropped wiki design, keyed on the record instead of a file.

```sql
CREATE TABLE IF NOT EXISTS document (
    project_id      BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    doc_id          VARCHAR(255) NOT NULL,
    doc_type        VARCHAR(128),
    origin          VARCHAR(32) NOT NULL DEFAULT 'record',  -- 'record' | 'upload'
    content_hash    CHAR(64) NOT NULL,   -- sha256 of rendered blocks (drives re-embedding)
    raw_hash        CHAR(64) NOT NULL,   -- sha256 of the raw record (drives metadata refresh)
    embed_model     VARCHAR(128) NOT NULL,
    profile_version INT,
    allowed_groups  TEXT[] NOT NULL,
    chunk_count     INT NOT NULL,
    indexed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, doc_id)
);
```

`content_hash = sha256(canonical JSON of the **rendered blocks** - text + breadcrumb - not of the
raw record)`. This matters because of extraction noise: a re-extraction that shifts a confidence
from 0.82 to 0.83 changes the raw record but not one character of what gets embedded. Hashing the
raw JSON there would re-embed an entire corpus to produce byte-identical vectors. Hashing the
rendered output makes "dirty" mean exactly "the thing we index changed".

Provenance-only changes therefore skip the embedding work but still need their metadata refreshed,
so a skip with a differing raw-record hash updates `chunks.metadata` and the Qdrant payload in
place - a cheap UPDATE, no embedding call. `raw_hash` is stored alongside `content_hash` to detect
that case.

A record is **dirty** when the rendered hash,
`embed_model`, `profile_version`, or `allowed_groups` differ from the stored row - otherwise the
POST returns `status:"skipped"` and costs zero embedding calls. `force=true` overrides.

Why each field earns its place: re-posting the same record is the normal case in an
extraction pipeline that retries; an embedding-model change makes old vectors incomparable and
must re-index everything; a profile edit changes the text that was embedded; a group change
must not leave stale labels in Qdrant.

**Delete propagation.** `IngestService.delete` gains: remove the `document` row, and remove
`doc_edge` rows where the doc is `dst_doc` (not only `src_doc`), so graph expansion cannot hop to
a page that no longer exists. `chunk_feedback` and `rag_trace` rows are kept deliberately -
labels are hard-won eval evidence keyed by `(doc_id, chunk_index)` and a record can return on the
next sync; traces are a record of what was actually answered.

**Ordering fix.** `delete()` currently removes Postgres rows before Qdrant points. LEARNINGS
section 13 states the opposite rule and the code contradicts it: delete the fallible store first,
so a Qdrant failure leaves Postgres intact and retryable instead of orphaning vectors forever.

## 6. Components

| Unit | Responsibility | Depends on |
|---|---|---|
| `RecordController` | `/records` POST + DELETE, validation, group check | `RecordIngestService` |
| `RecordIngestService` | hash, dirty check, render, delegate to `IngestService` | `RecordRenderer`, `DocumentRegistry`, `IngestService` |
| `RecordRenderer` | JSON + optional profile -> `(text, breadcrumb, metadata)` blocks | `ValueWrapper`, pure, no DB |
| `ValueWrapper` | detect and strip value wrappers; split value vs provenance | pure, no DB |
| `RenderProfile` + `ProfileRepository` | profile CRUD, version bump | JdbcTemplate |
| `DocumentRegistry` | `document` upsert / find / delete | JdbcTemplate |
| `MetadataFilter` + `FilterSql` / `FilterQdrant` | DSL parse, validate, render to each store | pure + client types |
| `IngestService` | unchanged contract, gains `metadata`/`docType` pass-through | existing |

`RecordRenderer` and the filter translators are pure functions - the two places where behavior is
subtle are both testable without a container.

## 7. Error handling

- Unknown filter path: **not** an error. It matches nothing, and the response carries a
  `warnings` entry. Extraction schemas differ per tenant; a 400 would make a shared UI unusable.
- Malformed filter (bad op, `range` without a bound, `in` with an empty list): 400. These are
  caller bugs, not data variance.
- Record that renders to zero blocks (all fields empty/excluded): 400 with the reason. Silently
  storing nothing is the failure mode that gets discovered a month later.
- Profile referencing paths absent from a record: ignored, no warning. Schemas drift.
- Wrapper-looking object with an unknown extra key: rendered generically, not unwrapped, and a
  `warnings` entry names the key. Failing open keeps the data; the warning is how a new
  extractor field gets noticed instead of quietly polluting vectors.
- Confidence that is not a number (string `"high"`, null): stored as-is under `_confidence_raw`
  and excluded from `_confidence.min`/`avg`, so one odd tenant cannot poison a numeric filter.
- Qdrant failure mid-ingest: existing behavior - throw, roll back the doc, no registry row, so
  the next POST retries cleanly.

## 8. Testing

**Unit (no container):**
- renderer: nested record with arrays; scalars-only record; deeply nested object; empty/null
  pruning; a docType with no profile; the same record with a profile that excludes, renames, and
  re-boundaries; oversized array element splitting while keeping its breadcrumb.
- value wrapper: a wrapped scalar renders the value only, and **no** rendered text anywhere in
  the record contains a bbox number or a confidence score - asserted over the whole rendered
  output, because this is the failure that quietly degrades every vector; wrapper with an
  unknown key falls back to generic rendering plus a warning; profile-declared wrapper keys;
  nested wrapper inside an array element; missing confidence produces no `_confidence` key.
- filter translation: each op to SQL and to a Qdrant filter; empty filter list produces no
  predicate in either store; number and date typing.
- confidence aggregation: `_confidence.min`/`avg` computed per chunk over numeric scores only.
- hash: canonical JSON so key order cannot change the hash.

**Integration (Testcontainers, existing pattern):**
- filtered search returns only matching records on all six backends, and an identical filter
  produces an identical hit set in pgvector and Qdrant.
- a filter that excludes the top vector hit changes results in `rerank` too - proves the filter
  runs before the over-fetch trim.
- graph expansion cannot return a neighbour that fails the filter.
- access label AND filter: a user without the group gets nothing even when the filter matches.
- unknown docType with no profile is ingested and retrievable.
- a `_confidence.min` range filter narrows results on every backend, and a low-confidence chunk
  is still retrievable when no such filter is sent.
- re-post identical record -> `skipped`, chunk count unchanged, no new embeddings.
- re-post with only a confidence value changed -> `metadata-refreshed`: no embedding call, but
  `chunks.metadata` and the Qdrant payload both carry the new score.
- profile edit -> only that docType re-indexes.
- DELETE removes chunks from Postgres **and** points from Qdrant, plus inbound edges and the
  registry row.

**Live evidence to record in LEARNINGS:** ingest a small set of records of two different types,
then run one question with and without a filter through `/compare` and record the recall and
latency difference. A filter that narrows 7,000 chunks to 40 should show up in the retrieve stage
of the trace.

## 9. Documentation to update when built

`docs/implementation-notes.md` (decisions and deviations, per the standing rule),
`docs/LEARNINGS.md` (new section on filtered retrieval and open-schema rendering),
`docs/ARCHITECTURE.md` (record ingest path + filter enforcement points),
`docs/RAG-MASTERY.md` (section 9 row 2 and row 4 re-score), `README.md` (new endpoints).
