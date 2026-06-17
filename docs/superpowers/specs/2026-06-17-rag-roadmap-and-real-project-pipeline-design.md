# RAG Roadmap + Real-Project Pipeline - Design Spec

**Date:** 2026-06-17
**Status:** Draft for review
**Type:** Roadmap / architecture spec (NOT a single implementation plan)
**Location:** `D:\project-fpt\springboot-rag`
**Supersedes scope of:** `docs/2026-06-13-springboot-rag-design.md` (v1, the built baseline) - this doc extends it forward.

---

## 1. Purpose and scope

Two coupled goals:

- **Part A - Sandbox RAG roadmap.** Decide the sequence of modern RAG methods to add to this learning sandbox, each as a new comparable backend behind `/compare`. Purpose is learning, not production.
- **Part B - Real-project pipeline shape.** Sketch the architecture of the real project (an incident knowledge base) at a high level: `upload -> parse-to-markdown -> extract-to-JSON -> index -> RAG search`. The sandbox is the de-risking vehicle: methods are proven here on local models before they go into the real project on Azure OpenAI.

> **Naming note:** the real target project is referred to throughout as **"the real project"**. Concrete library / API / parser names in Part B are **illustrative placeholders only** - they show the concept, the exact tool per stage is decided later.

**Why this is one spec:** Part B defines *what methods matter*; Part A *sequences learning them*. They share one contract (model-access abstraction). Deep implementation of each method - especially Graph RAG - is expected to get its own follow-up spec + plan.

### Scope boundaries (YAGNI)

- No auth, no multi-tenancy, no chat history in either part now. Add when the real project needs them.
- Agentic RAG and Multimodal RAG are **parked** (see Part D). Not built in this roadmap.

---

## 2. Current baseline (built, v1)

Already shipped and verified (see v1 design doc):

- Backends: Postgres FTS (keyword), pgvector, Qdrant, hybrid (RRF fusion of FTS + pgvector).
- `/compare` runs the same query through all backends side by side - ranking + latency visible.
- `EmbeddingProvider` interface already abstracts the model (Ollama `nomic-embed-text`, 768 dims), Azure swap planned.
- Stack: Java 21 / Spring Boot 3.5.6, raw `JdbcTemplate`, Qdrant Java client, Testcontainers.

The "RAG zoo" of named variants (LightRAG, ContextRAG, BalanceRAG, GraphRAG, Agentic RAG...) collapses to a few families: naive vector, hybrid, graph, agentic, multimodal. You are not outdated for missing each named paper - only if stuck at naive vector with no rerank. This roadmap climbs the families deliberately.

---

## Part A - Sandbox RAG roadmap

### A.1 Method ladder

Each step = one new comparable method, plugged into existing `SearchService` and surfaced as one more column in `/compare`. Ordered easy -> hard.

| # | Method | What is new | Plugs into | What `/compare` teaches |
|---|--------|-------------|-----------|--------------------------|
| 0 | Vector / FTS / Hybrid-RRF | done (v1) | - | baseline |
| 1 | **Reranker** | cross-encoder reorders top-N (e.g. `bge-reranker` via ONNX runtime or Ollama-served) | new `Reranker` interface wrapping any backend | precision lift: dumb top-50 -> smart top-10 |
| 2 | **Metadata filtering** | structured fields (system, severity, date) stored + applied as `WHERE` / Qdrant payload filter | new column / payload + filter param | retrieval is not only similarity: "payment-system incidents, last 30 days" |
| 3 | **Query transform** | HyDE + query rewrite (local LLM expands / rephrases the query before embed) | new `QueryTransformer` step before embedding | recall lift on vague / paraphrased queries |
| 4 | **Graph RAG** | extract entities + relations (LightRAG dual-level: local detail + global summary), retrieve a subgraph | new graph store + `GraphRepository` | relationship reasoning: "incidents sharing a root cause", cause chains |

### A.2 Invariant design rule

Every method follows the v1 pattern that already works:

- New capability = **new class behind an interface**. Existing `SearchService` orchestration, storage, and fusion code does not get rewritten.
- `/compare` gains one column per method. It stays the single learning dashboard (4 columns today -> up to 8).
- Same approach as the existing `EmbeddingProvider` abstraction.

### A.3 Flagged design decisions (resolve in per-method plan)

- **Reranker infra (DECIDED 2026-06-17):** sandbox uses **DJL (Deep Java Library)** to load a real cross-encoder (`bge-reranker-base`) as ONNX - local, offline, clean `predict(query, doc)` API. Note: Ollama has **no** native rerank endpoint (generation + embeddings only), so "Ollama-served cross-encoder" is not a real option (only LLM-as-reranker prompting, which is not a true cross-encoder).
  - *Alternative recorded - ONNX Runtime direct:* same model, but you wire tokenizer + tensors + padding by hand (~80 vs ~20 lines). More boilerplate, more bug surface, but exposes the internals. Use only if the goal is to learn tokenizer/tensor plumbing.
  - *Real-project reranker (open):* most likely a **hosted rerank API** (Cohere / Jina / Azure AI) to match the Azure setup - no self-hosted model ops. Self-hosted ONNX-direct only if the real project deliberately wants a local reranker. Decide at real-project time.
- **Graph store:** do **not** add Neo4j up front. Start with Postgres tables (`nodes`, `edges`) - Postgres is already running. Promote to Neo4j only if the graph outgrows SQL (stretch).
- **Effort split:** steps 1-3 are cheap, self-contained additions. Step 4 (Graph RAG) is a substantial chunk and should be its own sub-spec + plan.

### A.4 First implementation target

**Step 1 - Reranker.** Smallest, highest immediate precision payoff, and directly reused by the real project.

---

## Part B - Real-project pipeline shape

High-level architecture of the real project (incident knowledge base). Backed by Azure OpenAI. Architecture altitude only.

### B.1 Flow

```
+---------+   +--------------+   +---------------+   +---------------+   +------------+
| Upload  |-->| Parse        |-->| Extract       |-->| Index         |-->| Retrieve   |
| PDF/doc |   | -> Markdown  |   | MD -> JSON    |   | chunk + embed |   | (RAG)      |
+---------+   +--------------+   +---------------+   | + metadata    |   +------------+
                                                     | + graph (opt) |
                                                     +---------------+
```

### B.2 Stages

| Stage | Job | Tool (illustrative placeholder) | Notes |
|-------|-----|----------------------------------|-------|
| Upload | accept file, store raw, queue job | Spring + object store | async job, do not block on parse |
| Parse | PDF / docx -> clean markdown | Azure Document Intelligence, or marker / docling / RAG-Anything | parse quality is the #1 RAG quality driver. Garbage parse -> garbage RAG |
| Extract | markdown -> structured incident JSON | Azure OpenAI (LLM structured output) | schema = incident: `{title, system, severity, date, symptom, root_cause, resolution, ...}` |
| Index | chunk + enrich + embed + store JSON metadata (+ graph) | Azure embeddings + the sandbox tables | this is where the sandbox methods land |
| Retrieve | hybrid + rerank + filter (+ graph) -> answer | the sandbox methods, Azure swap | incident search + "similar / prevent" |

### B.3 Index content representation (the enriched-chunk pattern)

Untangling FTS vs vector vs JSON, since they are easy to conflate:

- **FTS** = keyword index (`tsvector`). No embedding.
- **Vector** = embedding. 
- **JSON** = mostly **filter metadata** + graph nodes, not the primary search content.

What content goes where:

| Content | FTS (keyword) | Vector (embed) | Filter | Graph |
|---------|---------------|----------------|--------|-------|
| Markdown chunks (prose) | primary | primary | - | - |
| JSON fields (system, severity, date) | optional | no | primary | nodes |
| JSON key text (root_cause, symptom, resolution) | enrich | enrich | partial | yes |

**Pattern (recommended):** primary searchable content = markdown chunks. **Enrich** each chunk's indexed text by appending key JSON fields (e.g. `root_cause`, `symptom`). Then FTS-index **and** embed that one enriched text. Reason: the extract step surfaces buried facts - you want "memory leak" hittable even if the prose mentioned it once. The JSON structured fields are *also* stored separately and reused as filter metadata and as graph nodes.

So: **not separate markdown-vs-JSON embeddings - one enriched text per chunk**, indexed both ways. Structured JSON reused for filter + graph.

### B.4 Flagged design decisions

- **Incident JSON schema = the contract** between Extract and Retrieve. It defines the metadata-filter fields (A step 2) and the graph nodes (A step 4). Worth nailing early. Open question: fixed schema vs flexible JSONB.
- **Parse step** = biggest unknown and biggest quality lever. Pick the parser via its own evaluation.
- **Async jobs:** upload returns immediately; parse / extract / index run in the background (incident PDFs are slow to process).

---

## Part C - Sandbox <-> real-project connection

The contract that makes the sandbox worth building: methods proven locally port to the real project by config swap, not rewrite.

```
SANDBOX (local Ollama)                 REAL PROJECT (Azure OpenAI)
----------------------                 ---------------------------
EmbeddingProvider  -- swap -->         AzureEmbeddingProvider
Reranker           -- swap -->         (same interface, Azure / hosted)
QueryTransformer   -- swap -->         (same interface, Azure LLM)
SearchService      -- port -->         same orchestration
GraphRepository    -- port -->         same graph logic
RRF / metadata SQL -- port -->         identical SQL
```

Only thing that does **not** port: the exact parse / extract tooling (real-project-specific, e.g. Azure Document Intelligence). Everything in the retrieve path ports behind the existing interfaces.

`/compare` evolution: 4 columns today -> up to 8 (+rerank, +filter, +query-transform, +graph). Same endpoint, the learning dashboard.

---

## Part D - Parked stretch (for the real project, later - not built now)

Recorded at the user's request for future reference (was "Approach C"):

- **Agentic RAG** - LLM-driven multi-step retrieval loop (decide what / when to retrieve, iterate).
- **Multimodal RAG** - text + tables + images (RAG-Anything style), for screenshots / diagrams inside incident PDFs.

Less reusable for the first real-project version and risk over-engineering the learning sandbox, so deferred. Revisit when the real project demands them.

---

## Part E - Open questions for the planning phase

1. ~~Reranker infra~~ - RESOLVED 2026-06-17: DJL cross-encoder for sandbox (see A.3). ONNX-direct noted as alternative; real-project reranker (hosted API vs self-hosted) left open until real-project time.
2. Graph store: Postgres `nodes`/`edges` (start) vs Neo4j (stretch) - and the LightRAG dual-level retrieval shape.
3. Incident JSON schema: fixed columns vs flexible JSONB; which fields are filterable.
4. Parser choice for the real project (its own evaluation).
5. Whether Graph RAG (A step 4) becomes its own spec before implementation (likely yes).

---

## Part F - Implementation sequencing

This spec is a roadmap, not one plan. Suggested order:

1. **Step 1 Reranker** - first plan + implementation target.
2. Step 2 Metadata filtering.
3. Step 3 Query transform (HyDE + rewrite).
4. Step 4 Graph RAG - own spec + plan.
5. Real-project pipeline (Part B) - separate effort once sandbox methods are proven; reuses the abstractions via Azure swap.
