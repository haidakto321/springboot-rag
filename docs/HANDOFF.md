# Handoff - read this first

**New machine, new teammate, or future you after a long gap: start here.** Everything needed to
pick this repo up lives in the repo. Nothing important is stored only in a chat history or an
assistant's memory.

## What this is

`springboot-rag` is a **self-study sandbox** for retrieval-augmented generation in Java 21 /
Spring Boot 3.5.6. It ingests markdown, indexes it in Postgres (FTS + pgvector) **and** Qdrant, and
searches the same corpus six ways - `fts`, `pgvector`, `qdrant`, `hybrid` (RRF), `rerank`
(cross-encoder), `graph` - so the techniques can be compared side by side on identical data via
`/compare`.

It has since grown past "compare four backends" into the parts that decide whether a RAG system is
deployable: permission-aware retrieval, prompt-injection defence, per-request tracing, human
relevance labels, and a retrieval regression gate. It is still a laboratory, not production.

## Read these, in order

| Document | What it gives you |
|---|---|
| `README.md` | Prerequisites, run commands, **login credentials**, endpoints, configuration |
| `docs/ARCHITECTURE.md` | What actually happens on a request - diagrams of search, chat, ingest, plus the failure map and file map |
| `docs/LEARNINGS.md` | Why each piece exists, with measured numbers. The most valuable file here |
| `docs/RAG-MASTERY.md` | The gap list: what is still missing, scored honestly, with a drill for each |
| `docs/ROADMAP.md` | Feature backlog, what is built and what is not |
| `docs/implementation-notes.md` | Running log of decisions and deviations, newest at the bottom |
| `docs/2026-06-13-springboot-rag-design.md` | Original design spec |

## First run on a new machine

```bash
docker compose up -d          # postgres + qdrant
ollama serve                  # plus: ollama pull nomic-embed-text && ollama pull qwen3:4b
./mvnw spring-boot:run        # http://localhost:8085
./mvnw test                   # full suite, needs Docker, does not need Ollama
```

**The app requires a login.** Sandbox users are defined in `application.yml` under
`app.security.users`: `alice` / `alice` (groups `public`, `hr`) and `haiks` / `123123` (groups
`public`, `eng`). The browser prompts once; API calls need `-u alice:alice`. Every retrieval path
filters by the caller's groups, so an unauthenticated call is a `401` and a caller with no groups
sees nothing. Details in `README.md` and `docs/LEARNINGS.md` section 16.

## State that lives outside the repo

Some things cannot be committed. If you are setting up elsewhere, these are what you will be
missing:

- **The 428-page wiki corpus** (project `docmaster`, 7,536 chunks). Private, never committed. The
  wiki eval (`-Dgroups=eval-wiki`) **skips** when it is absent instead of failing, so a fresh clone
  is not broken - it just cannot run that gate. `docs/ROADMAP.md` tracks the frozen-corpus fix.
- **The local Postgres and Qdrant volumes.** `schema.sql` runs at startup and is idempotent, so a
  new machine gets an empty but correct database. Re-import documents through the UI.
- **Ollama models.** `nomic-embed-text` (embeddings, 768-dim) and a chat model - default
  `app.chat.model=qwen3:4b`. A different embedding model means a **full re-index**: vectors from
  different models are not comparable, and `schema.sql` pins `vector(768)`.
- **Feedback labels and traces.** Both are local operational data (`chunk_feedback`, `rag_trace`).
  A new machine starts with none; the feedback eval skips below 10 labels.

## Lessons already paid for (do not re-derive)

The full versions are in `docs/LEARNINGS.md`; this is the index.

- **Exact codes (`INV-5575`) → FTS wins, vectors fail. Paraphrases → vectors win, FTS returns
  nothing.** Hybrid covers both, but only when both arms return something (§4).
- **`plainto_tsquery` ANDs every term**, so a mixed code + concept query matches nothing. Use
  `websearch_to_tsquery` (§4).
- **"Hybrid beats FTS" is not a law.** On the real wiki corpus, pgvector, qdrant, hybrid, rerank
  and graph all tie at recall@5 0.909 - hybrid adds nothing there (§11).
- **The cross-encoder made retrieval slightly worse** on that corpus (MRR 0.919 → 0.909) (§14).
- **Structural GraphRAG returned an identical top-10 to plain hybrid on every query tried** (§14).
- **`think:false` does not stop a reasoning model** - it dumps tag-less chain-of-thought into the
  answer. Always send `think:true` and read the separate `thinking` field (§12).
- **Retrieval filtering is a security control**, and Postgres and Qdrant have opposite empty-set
  semantics: an empty SQL array overlap is false (fail closed), an empty Qdrant `should` clause
  matches everything (fail open) (§16).
- **Prompt rules are requests; only code is a control.** The system prompt told the model never to
  reveal credentials in the material, and it did anyway (§17).
- **Generation is 97% of request latency here** (210 s of 218 s), retrieval 0.04%. Optimise the
  answer model, not the vector store (§18).
- **Asking a reasoning model for one word does not get you one word - constraining its output
  does.** An unconstrained router call spent its whole token budget narrating and never answered;
  the same call with a JSON schema was 8/8 correct at 3.4 s, and with thinking on it was correct
  but took 44 s (§21).

## Current state (2026-08-08)

- On `master`, working tree carries the query-routing change (uncommitted - the user commits).
  Suite: **414 tests, 0 failures, 3 skipped** (the 3 are manual DJL model-download tests).
- RAG-MASTERY scorecard: rows 1, 2, 3 and 6 at 2, row 4 at 2 (routing now exists but extraction is
  still 52 s p50 and there is no fan-out/decomposition/HyDE), rows 5, 7 and 8 at 1. No zeros left.
- Two eval gates, both run on demand, neither in CI: `-Dgroups=eval-wiki` (needs the private wiki
  corpus, so one machine only) and `-Dgroups=eval-records` (Testcontainers + a committed synthetic
  corpus, runs on a fresh clone; ~30 minutes because extraction is a live model call per question).
- Known gaps worth knowing before you build on this: ingest is one-shot (no re-sync, no upstream
  delete detection), `/search` and `/compare` are not traced and not routed, `app.rerank.maxLength`
  is dead config, there is no UI for records or filters beyond the answer chips, and the feedback
  eval still has no real thumbs behind it, so "does the cross-encoder earn its latency" is
  unanswered.
- **Machine state matters for every latency number here.** Ollama went from 3.5 s to 256 s for the
  same 10-token call purely from memory pressure (orphaned JVMs and containers). Check
  `docker ps` and free RAM before trusting a measurement.

## Convention notes

- Build with `./mvnw`, never a system Maven.
- The frontend is plain HTML/CSS/JS on purpose - no framework, no build step.
- No new dependencies without a deliberate decision; `pom.xml` is small for a reason.
- Every non-obvious decision goes in `docs/implementation-notes.md` at the time it is made.
- Internal names from any day-job codebase stay out of this repo - it is meant to be shareable
  on its own.
