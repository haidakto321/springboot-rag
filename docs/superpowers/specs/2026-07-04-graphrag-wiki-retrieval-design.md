# GraphRAG Wiki Retrieval - Design

Date: 2026-07-04
Status: Draft (brainstorming output, pending review)
Scope: Wiki structural + entity GraphRAG as a new retrieval backend. Full GraphRAG
(communities / global search) and code ingestion are explicit non-goals for this build.

## 1. Goal

Add **graph-based retrieval** as a new backend in the existing search-comparison sandbox,
so it sits next to `fts | pgvector | qdrant | hybrid | rerank` in `/compare` and `/search`.

Primary real use case: **discovery of forgotten / orphaned knowledge** in a large,
interlinked wiki - "a feature another team built long ago that nobody on the current team
knows about". The win over manual wiki search + over plain hybrid is:

- start from what you *do* know (a service, a concept) and **hop** to what you don't;
- surface **orphan pages** (no inbound links = why they are "lost") by reconnecting them
  through shared entities.

Success = the `graph` backend measurably beats `hybrid`/`rerank` on multi-hop / discovery
queries over the wiki, measured in the existing eval harness. If it does not win, that is a
valid sandbox result too.

## 2. Corpus

An Azure DevOps wiki clone (local git repo; exact path configured locally, not committed):

- 449 `.md` pages, 6.8 MB text
- 3,851 internal links, 360/449 pages have >=1 internal link (dense)
- 46 `.order` files (hierarchy)
- 2,071 `.attachments` image refs (stripped, not ingested)
- Link forms seen: `](/Cross-Page)`, `](/Path/Sub-Page)`, in-page `](#anchor)` TOC jumps
- git clone -> per-page last-commit date available for recency (see 6b)

Ingested as its **own project** inside the existing multi-project workspace feature. No new
top-level infra - reuse Postgres, Qdrant, Ollama, the existing chunk table and pipeline.

## 3. Two edge sources

The graph is built from two independent edge sources; either can be toggled.

### 3a. Structural edges (free, exact, fast - no LLM)

Parse the wiki's own structure:

- **Links**: `](/Page)` cross-page refs -> `doc_edge(src_doc -> dst_doc, kind=link)`.
  Filter out `](#anchor)` in-page jumps and `](...attachments...)` image refs.
- **Hierarchy**: `.order` files + folder tree -> `doc_edge(parent -> child, kind=hierarchy)`.

Gives ~3,800 exact edges immediately, before any AI. Cheap and always on.

### 3b. Semantic edges (LLM, fuzzy, richer - the orphan reconnector)

Per chunk, qwen3 (reuse `app.chat.model` or a dedicated extractor model) extracts
**entities** + **relations**:

```
chunk -> { entities: [{name, type}], relations: [{src, rel, dst}] }
```

Produces:
- `entity` rows (normalized name + type)
- `chunk_entity` mention links (which chunk mentions which entity)
- `entity_edge` relations between entities (weighted by co-mention / extraction)

This is what reconnects orphans: an orphan page with no links still *mentions*
"PaymentsService"; so does a well-known page; the shared entity bridges them.

Toggleable because it is the slow, costly part.

## 4. Data model (Postgres, additive)

Reuse the existing `chunk` table unchanged. Add:

```
entity(
  id            bigserial pk,
  project_id    bigint,
  name_norm     text,          -- lowercased/trimmed surface form for matching
  name_display  text,
  type          text,          -- e.g. service, feature, team, concept
  unique(project_id, name_norm)
)

chunk_entity(
  chunk_id      bigint,        -- fk chunk
  entity_id     bigint,        -- fk entity
  primary key(chunk_id, entity_id)
)

doc_edge(
  id            bigserial pk,
  project_id    bigint,
  src_doc       text,          -- docId
  dst_doc       text,          -- docId (may be unresolved if link target missing)
  kind          text,          -- link | hierarchy
  unique(project_id, src_doc, dst_doc, kind)
)

entity_edge(
  id            bigserial pk,
  project_id    bigint,
  src_entity    bigint,
  dst_entity    bigint,
  relation      text,          -- extracted relation label, or 'co-mention'
  weight        double precision,
  unique(project_id, src_entity, dst_entity, relation)
)
```

All scoped by `project_id` so graph retrieval respects the existing project/group filters.
JSONB left available for extra entity/relation metadata if needed (per Postgres+JSONB pref).

**Document recency** - add an `updated_at timestamptz` attribute at the document level
(all chunks of a doc share it; store on the chunk rows or a small per-doc table). Captured at
ingest (section 5) and used for conflict tiebreak (section 6b). `SearchHit` carries it so
answers can cite which page is newer.

## 5. Ingest flow

Extends the existing markdown ingest; does not replace it.

```
ingest wiki page (existing MarkdownChunker path)
  -> chunks + embeddings in Postgres + Qdrant   [unchanged]
  + parse links/hierarchy   -> doc_edge          [structural, always]
  + per chunk: qwen3 extract -> entity, chunk_entity, entity_edge   [semantic, toggle]
  + capture updated_at (git last-commit date of the page; fallback file mtime)
```

The uploader passes `updated_at` in with the document; when ingesting a wiki clone it is
resolved via `git log -1 --format=%cI -- <page>.md`. If unavailable, fall back to file mtime,
then to ingest time. Version numbers are NOT parsed (not standard in these pages); date only.

**Cascade delete on re-ingest (critical).** Re-uploading the same `docId` already wipes and
rebuilds its chunks in Postgres + Qdrant (`IngestService.ingestChunks` -> `delete()`). The
graph tables MUST join that same cascade, or stale edges accumulate:

On `delete(projectId, docId)` also remove:
- `chunk_entity` rows for that doc's chunks,
- `doc_edge` where `src_doc = docId` (and optionally dangling `dst_doc`),
- `entity_edge` / `entity` rows orphaned by the deletion (entities with no remaining
  `chunk_entity` are garbage-collected).

This reuses the existing dual-store cascade-delete pattern (see docs/LEARNINGS.md).

## 6. Retrieval - the `graph` backend

New `SearchService` case `"graph"`, returning `List<SearchHit>` like every other backend.

Algorithm (local GraphRAG):

```
1. Extract query entities (qwen3, same extractor) from the query text.
2. Seed entities = query entities matched to `entity` by name_norm (exact, then fuzzy).
3. Expand 1-hop over entity_edge -> neighbor entities.
4. Candidate chunks = chunks linked via chunk_entity to (seed ∪ neighbor) entities,
   UNION the doc-edge neighbors of seed chunks (structural hop).
5. UNION with plain hybrid hits (preserve vector/keyword recall; graph only adds).
6. Rerank the union with the existing Reranker -> trim to topK.
```

**Fallback:** if the query yields no matchable entities, degrade to `hybrid` so the `graph`
backend never returns empty. Same defensive posture as the existing `rerank` -> `hybrid`
degrade.

Plugs into `/search?type=graph`, `/compare` (new `graph` column), and is available to
`/ask` and `/chat` retrieval the same way `rerank` is.

## 6b. Recency / conflict handling

Two pages describing the same feature are detected by **shared entities** (they link to the
same `entity` via `chunk_entity`). When retrieval surfaces chunks from both:

- **Tiebreak by recency**: among candidate chunks of comparable relevance that share the
  seed entity, prefer the doc with the newer `updated_at`. Applied as a light re-rank nudge,
  NOT a hard filter (an older page may still hold the only answer).
- **Expose the date**: `updated_at` rides along in `SearchHit`, so `/ask` and `/chat` can
  cite it - e.g. *"per the newer page (2026-06) ..."* - and the LLM can flag when an older
  and newer source disagree.

Out of scope for v1: an automated "this page is stale" detector or a dedicated conflict UI.
v1 only captures the date, nudges ranking, and surfaces it in citations.

## 7. Configuration

```
app.graph.enabled=true            # feature flag
app.graph.edges=both              # structural | semantic | both
app.graph.extract-model=          # blank = reuse app.chat.model
app.graph.min-mentions=1          # entity kept only if mentioned >= N times (noise floor)
app.graph.neighbor-hops=1         # traversal depth (keep 1 for v1)
```

Entity extraction is opt-in via `edges=structural` for a fast first pass, `both` for full.

## 8. Testing

- **Unit**: link parser (`md text -> doc_edge`, correct filtering of anchors/attachments);
  entity extractor with a mocked LLM; graph retrieval algo over a hand-built fixed graph
  (assert seed -> neighbor -> chunk selection + hybrid union + fallback).
- **Integration** (Testcontainers, fake embeddings, mocked/staked extractor): ingest a small
  fixture slice of the real wiki; assert an **orphan page** (no links, shared entity) IS
  retrieved by `graph` but is NOT a top hit under `hybrid`. This is the headline behavior.
- **Cascade**: re-ingest a doc, assert its old `doc_edge`/`chunk_entity`/orphaned `entity`
  rows are gone (no stale-edge accumulation).
- **Eval**: add the `graph` column to the existing eval harness; run top-K recall / MRR /
  hit@1 over a wiki slice, `graph` vs `hybrid` vs `rerank`. This is the whole point.

## 9. Non-goals (YAGNI)

- **Full GraphRAG**: Leiden community detection, community summaries, global search - phase 2,
  only if this build shows promise.
- **Code ingestion**: user maintains hand-written `.md` (structure/architecture) and re-uploads
  on change; no code-to-doc auto-generation, no `CodeChunker`. Code is a possible future
  *project*, not part of this build.
- **Auto-reupload on md change** (git hook): staleness is handled by the user re-uploading;
  same `docId` auto-replaces. No automation now.
- **Graph visualization UI**: not in this build.
- **Entity coreference / fancy name resolution**: v1 does simple lowercase+trim normalization
  only.

## 10. Risks + mitigations

- **Extraction quality (qwen3 local)**: noisy/missed entities -> broken edges, possibly worse
  than plain RAG. Mitigate: `min-mentions` noise floor, type filtering, name normalization,
  and the hybrid UNION + fallback so graph can only *add* recall, never subtract it.
- **Ingest slowness**: 449 pages x multiple chunks x 1 LLM call. Mitigate: structural edges
  are instant and independent; semantic extraction is opt-in and can run as a one-time batch.
- **Name resolution**: same entity, different surface forms fragments the graph. v1 accepts
  this (lowercase/trim only); note as a known limitation for a later pass.
- **Corpus scoping**: 449-page wiki is heavy for the sandbox, but comparison IS the purpose;
  kept as its own project so it does not pollute the small test corpora.
```
