# springboot-rag - Design Spec

**Date:** 2026-06-13
**Status:** Draft for review
**Type:** Standalone self-study / research sandbox (NOT part of docmaster)
**Location:** `D:\project-fpt\springboot-rag` (sibling to `docmaster`, `rag-python`)

---

## 1. Purpose

A hands-on sandbox to learn and compare text-search techniques in Java:

- Postgres native full-text search (FTS)
- pgvector semantic (vector) search
- Qdrant (dedicated vector database) search
- Hybrid search (fusion of keyword + vector via Reciprocal Rank Fusion)

Goal is learning and a reusable reference, not production. The centerpiece is a
`/compare` endpoint that runs the same query through all backends side by side so
the differences in ranking, score, and latency are visible.

This complements the existing `rag-python` project (which already shows the Python
+ pgvector + BM25 + reranking path). This sandbox builds the same ideas in Java -
the daily language - so the learning transfers to docmaster's future search feature.

### Non-goals (YAGNI)

- No authentication.
- No multi-tenancy.
- No chat / conversation history.
- No reranking in v1 (noted as future stretch).
- No custom UI (use Swagger UI / curl).

---

## 2. Stack

| Concern | Choice | Note |
|---------|--------|------|
| Language | Java 21 | |
| Framework | Spring Boot 3 | |
| Keyword + vector store 1 | PostgreSQL + pgvector | via Docker |
| Vector store 2 | Qdrant | via Docker, dedicated vector DB |
| Embeddings | Ollama local (`nomic-embed-text`, 768 dims) | free, offline, no API keys |
| Postgres access | raw `JdbcTemplate` | keep SQL visible for learning |
| Qdrant access | official Qdrant Java client | |
| Connection pool | HikariCP (Spring default) | fixes rag-python's single-shared-connection anti-pattern |
| Tests | JUnit 5 + Testcontainers | pgvector image + Qdrant container |

### Embedding provider is pluggable

```java
public interface EmbeddingProvider {
    float[] embed(String text);
    int dimension();
}
```

- v1 implementation: `OllamaEmbeddingProvider` (768 dims).
- Adding Azure OpenAI later = one new class `AzureEmbeddingProvider` + a config
  switch. Search / storage / fusion code does NOT change.
- The real cost of switching providers is the **vector dimension** (nomic = 768,
  Azure `text-embedding-3-small` = 1536): the pgvector column and Qdrant collection
  are created at a fixed dimension, so switching means a new column/collection +
  re-ingesting the corpus. Dimension is config-driven (`app.embedding.dimension`)
  to keep the switch painless. In a sandbox, re-ingest is cheap.

---

## 3. Architecture

Layered, borrowed from `rag-python` (API -> service -> repository), with its known
anti-patterns fixed.

```
            ┌──────────────────────────────┐
            │  API layer (REST controllers) │
            │  IngestController             │
            │  SearchController             │
            └───────────────┬──────────────┘
                            │
            ┌───────────────▼──────────────┐
            │  Service layer                │
            │  IngestService                │
            │  SearchService (orchestrate + │
            │                 fuse)         │
            └───────┬───────────────┬───────┘
                    │               │
        ┌───────────▼───┐   ┌───────▼─────────────┐
        │ EmbeddingProvider │ Repositories        │
        │ (Ollama)          │ PgFtsRepository     │
        └───────────────┘   │ PgVectorRepository  │
                            │ QdrantRepository    │
                            └───────┬─────────────┘
                                    │
                    ┌───────────────▼───────────────┐
                    │ Postgres + pgvector  |  Qdrant │
                    └───────────────────────────────┘

        Util: RrfFusion (Reciprocal Rank Fusion, k=60)
              Chunker  (word/char chunks with overlap)
```

### Components

| Component | Responsibility |
|-----------|----------------|
| `IngestController` | `POST /ingest` |
| `SearchController` | `GET /search`, `GET /compare`, `DELETE /docs/{docId}`, `GET /health` |
| `IngestService` | chunk -> embed -> store in Postgres + Qdrant |
| `SearchService` | run a chosen backend, or all backends for compare; apply RRF for hybrid |
| `EmbeddingProvider` / `OllamaEmbeddingProvider` | text -> float[] |
| `PgFtsRepository` | Postgres FTS (`tsvector` + `ts_rank`) |
| `PgVectorRepository` | pgvector similarity (`embedding <=> :q`) |
| `QdrantRepository` | Qdrant vector search |
| `RrfFusion` | merge two ranked lists by reciprocal rank |
| `Chunker` | split text into overlapping chunks |

### Anti-patterns fixed (from rag-python CONCERNS.md)

- **Shared single DB connection** -> HikariCP pool (Spring default).
- **Pipeline steps assume field order** -> not applicable here (no step chain);
  services validate inputs explicitly.

---

## 4. Data model

### Postgres `chunks`

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE chunks (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    doc_id      VARCHAR(255) NOT NULL,
    chunk_index INT NOT NULL,
    content     TEXT NOT NULL,
    tsv         tsvector GENERATED ALWAYS AS (to_tsvector('english', content)) STORED,
    embedding   vector(768) NOT NULL,
    created_at  TIMESTAMP DEFAULT now()
);

CREATE INDEX idx_chunks_tsv ON chunks USING gin (tsv);
CREATE INDEX idx_chunks_embedding ON chunks USING hnsw (embedding vector_cosine_ops);
```

### Qdrant `chunks` collection

- Vector size: 768, distance: Cosine.
- Payload: `{ doc_id, chunk_index, content }`.
- Point id: same id as the Postgres row (so results line up across backends).

---

## 5. API

### `POST /ingest`
Request: `{ "docId": "string", "text": "long text..." }`
Behavior: chunk text -> embed each chunk -> insert into Postgres + upsert into Qdrant.
Response: `{ "docId", "chunksStored" }`.

### `GET /search`
Params: `q` (query), `type` = `fts | pgvector | qdrant | hybrid`, `topK` (default 10).
Response: ranked list of `{ id, docId, chunkIndex, content, score }`.

- `fts`: `ts_rank(tsv, plainto_tsquery('english', :q))`.
- `pgvector`: embed `q` -> `ORDER BY embedding <=> :qvec`.
- `qdrant`: embed `q` -> Qdrant search.
- `hybrid`: RRF over `fts` + `pgvector` results (k = 60).

### `GET /compare`  (study centerpiece)
Params: `q`, `topK`.
Behavior: run `fts`, `pgvector`, `qdrant`, `hybrid` for the same query.
Response: side-by-side results per backend, each with score and elapsed milliseconds,
so ranking and latency differences are directly visible.

### `DELETE /docs/{docId}`
Remove a document's chunks from Postgres + Qdrant.

### `GET /health`
Liveness + dependency reachability (Postgres, Qdrant, Ollama).

---

## 6. Hybrid fusion (the key lesson)

Reciprocal Rank Fusion. Each backend produces a ranked list; a result's fused score
is the sum over lists of `1 / (k + rank)`, with `k = 60`. RRF uses only rank
position, so it does not need the keyword and vector scores to share a scale (which
is exactly why it is the robust industry default). v1 fuses FTS + pgvector; Qdrant
can optionally be added to the fusion as an experiment.

---

## 7. Infrastructure

`docker-compose.yml`:
- `postgres` (pgvector-enabled image, e.g. `pgvector/pgvector:pg16`).
- `qdrant` (`qdrant/qdrant`).
- Ollama runs on the host (`ollama serve` + `ollama pull nomic-embed-text`); the app
  reaches it at `http://localhost:11434`. (Ollama can be added to compose later.)

Config keys (e.g. `application.yml`):
- `app.embedding.provider=ollama`
- `app.embedding.model=nomic-embed-text`
- `app.embedding.dimension=768`
- `app.ollama.base-url=http://localhost:11434`
- datasource + Qdrant host/port.

---

## 8. Testing

- Testcontainers: pgvector Postgres container + Qdrant container.
- Integration: ingest a small known corpus; assert each backend returns the expected
  top hit for a few crafted queries (one exact-term query, one paraphrase query) to
  demonstrate FTS vs vector strengths.
- Unit: `RrfFusion` correctness; `Chunker` boundaries/overlap.
- Ollama in tests: either a fake `EmbeddingProvider` returning deterministic vectors,
  or skip embedding-dependent tests when Ollama is absent. (Decide in plan.)

---

## 9. Future stretch (documented, not built v1)

- Reranking via a hosted API (Cohere Rerank / Jina) - Java-friendly, no local model.
- HyDE (hypothetical document embeddings) query expansion.
- Query rewriting.
- A tiny HTML page for the `/compare` view.
- Multi-tenant scoping (a `tenant` column + `WHERE tenant = ?`) - mirrors docmaster,
  added only if used as a closer docmaster prototype.

---

## 10. Relation to docmaster

The transferable parts - table design, FTS SQL, pgvector SQL, RRF fusion, the
per-source repository split - port directly to docmaster's parked search feature
(Approach B: custom `ade_chunk_embeddings` table with explicit `tid` + tenant-scoped
finder, hybrid SQL, indexing hook in `AdeParseJobProcessor` SUCCESS). The only piece
that does not transfer is local reranking (low impact for a ranked-list UI; use a
hosted reranker later if needed).
