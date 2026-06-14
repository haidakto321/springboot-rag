# Handoff - read this first

**If you are a future session (especially working on docmaster's search feature): start here.**

## What this project is (5 lines)

`springboot-rag` is a standalone **self-study sandbox** (NOT production, NOT part of docmaster). It ingests text and searches it 4 ways - Postgres FTS, pgvector, Qdrant, hybrid (RRF) - so the techniques can be compared side by side via a `/compare` endpoint. Built in Java 21 / Spring Boot 3.5.6. The point was to learn the FTS-vs-vector-vs-hybrid tradeoffs in Java before building docmaster's parked search feature.

## Read these, in order

1. `docs/2026-06-13-springboot-rag-design.md` - design spec. **Section 10 "Relation to docmaster"** maps exactly which parts port over.
2. `docs/implementation-notes.md` - the real knowledge: decisions, deviations, 3 live search lessons, the second code review, and the fixes applied. This is where the learning lives.
3. `docs/plans/2026-06-13-springboot-rag.md` - full task-by-task build plan with complete code.

## Key lessons learned (so you don't re-derive them)

- **Exact codes/IDs (e.g. `INV-5575`) -> FTS wins, vectors fail.** Embeddings can't tell literal codes apart.
- **Paraphrase/synonym (e.g. "unpaid bills past deadline" vs "overdue") -> vectors win, FTS returns empty.** Keyword search is blind to synonyms.
- **Hybrid (RRF) only helps when BOTH arms return useful lists.** If one arm is empty, hybrid = the surviving arm.
- **`plainto_tsquery` ANDs all terms** -> a mixed code+concept query matches zero chunks. Use **`websearch_to_tsquery`** instead (supports `OR`, `"phrase"`, `-negation`; bare words still AND). This is what makes hybrid actually blend on multi-topic queries.
- **pgvector and Qdrant tie on quality** at small scale (same vectors, same HNSW+cosine). Choose between them by ops/scale, not ranking.
- **FTS text-search config is per-language** (`'english'` hardcoded here). Vectors are language-agnostic - a real vector advantage if docmaster is multilingual.
- **RRF is pure arithmetic**, no library: `score = sum of 1/(k+rank)`, k=60.

## What ports to docmaster (per design s10)

Table design, FTS SQL, pgvector SQL, RRF fusion, per-source repository split -> all port directly to docmaster's custom `ade_chunk_embeddings` approach (explicit `tid` + tenant-scoped finder, hybrid SQL, indexing hook in `AdeParseJobProcessor` SUCCESS). Local reranking does NOT transfer (use a hosted reranker if needed).

## Scope decision still open for docmaster's feature

Customer intent was being clarified. The build differs sharply:
- **Keyword search only** -> FTS + dedup-by-doc + pagination + `ts_headline` snippets. Postgres only; drop Qdrant/Ollama.
- **Semantic search** -> add vectors + hybrid (this whole sandbox).
- **RAG (prompt -> written answer)** -> hybrid retrieval + an LLM generation step (new piece; topK small 3-8) + a "say I don't know" guardrail.

Confirm which before building - it changes everything (topK philosophy, infra, whether an LLM is involved).

## Current state

v1 built, 8 tests green (incl. Testcontainers integration), all 3 review findings fixed and verified live. See `implementation-notes.md` for the fix log.
