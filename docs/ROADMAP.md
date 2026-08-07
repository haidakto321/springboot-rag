# Smart Search Roadmap

Feature backlog for the knowledge-base UI, beyond the initial redesign. Ordered by the
agreed build sequence. Each unit is built + verified before the next.

## In progress / planned units

### Unit A - Compare UI  ✅ done (2026-07-02)
Third sidebar screen "Compare backends": one query run through all 5 backends
(fts, pgvector, qdrant, hybrid, rerank) shown side-by-side with per-backend latency and
ranked hits. Frontend-only; reuses the existing `GET /compare` endpoint.

### Unit B - Streaming chat  ✅ done (2026-07-03)
Merged "streaming answers" + "conversational follow-up".
- `POST /chat/stream` streams LLM tokens live as NDJSON frames (token* -> sources ->
  done/error) via StreamingResponseBody; `ChatProvider.chatStream` reads Ollama's
  `stream:true` NDJSON. Answer types out word-by-word.
- Stateless session memory: client sends the conversation each turn, capped to the last
  10 messages; retrieval runs on the latest user message.
- The Ask screen is now a chat thread with live token rendering and citation chips.
- Note: needs `spring.mvc.async.request-timeout` raised or long generations get cut with
  an InterruptedException.

**Condense-question retrieval  ✅ done (2026-07-03)** (was deferred from Unit B)
- For vague follow-ups ("tell me more", "why?"), `ChatService` first rewrites the
  conversation history + new question into a standalone search query (one non-streaming
  LLM call) and retrieves with that; the answer is still generated from the original
  question. First turn skips it; condensation failure falls back to the raw question.
  Toggle via `app.chat.condense-followups` (default true).

### Unit C - Document filter  ✅ done (2026-07-03)
- Scope search / ask / compare to a chosen subset of documents.
- Backend: `doc_id IN (...)` filter across pg (fts + pgvector) and Qdrant repos;
  threaded through `SearchService` + `ChatService`; `docIds` param on `/search`,
  `/compare`, and the `/chat/stream` body.
- UI: document scope chips on Search & Ask and Compare (all selected = no filter).

## Medium-value backlog  ✅ all done (2026-07-03)

- **Recent searches / history** ✅ - `<datalist>` of the last 8 queries (localStorage),
  recorded on submit.
- **Result -> open in context** ✅ - click a search or compare hit to jump to that document's
  chunk view, scrolled + highlight flash. `SearchHit.chunkIndex` drives the target.
- **Pagination / "load more"** ✅ - `topK` starts at 10; a "Load more" button fetches +10 more.
- **Snippet windowing** ✅ - results show the best-matching passage window centered on the first
  query-term match, with a "Show full / Show less" toggle.
- **Copy answer / richer citations** ✅ - Copy button on assistant answers; citation chips gain
  an "↗" that opens the cited chunk in its document (needed `chunkIndex` on `AskResponse.Source`).

## Low-value / polish  ✅ all done (2026-07-03)

- **Keyboard shortcut** ✅ - `/` focuses the search box (jumps to Search & Ask).
- **Search-as-you-type** ✅ - debounced live search (450ms, min 2 chars); recorded only on submit.
- **Thumbs up/down feedback** ✅ - per-answer 👍/👎, toggled, logged to localStorage (local-only,
  no backend).
- **Empty-state hint** ✅ - "no results" suggests switching mode (keyword <-> semantic).

### Multi-project workspaces  ✅ done (2026-07-04)
- Every document belongs to a **project**. Projects optionally share a `group_name` label
  (emergent groups - no separate groups table, just a string column on the project row).
- On startup: a **Default** project is seeded; all existing chunks are backfilled to it (zero-downtime migration).
- New REST surface: `POST/GET /projects`, `PATCH/DELETE /projects/{id}`, `GET /groups`, plus
  project-scoped document routes (`POST/GET /projects/{id}/documents`, `DELETE`, chunk listing).
- `/search`, `/compare`, `/ask`, and `/chat/stream` accept `projectId` to scope retrieval to one
  project; `group=true` widens retrieval to all projects sharing the active project's group name.
- Legacy flat endpoints (`/documents`, `/search`, `/ask`, `/compare`) continue to work, targeting
  the Default project - no breaking change.
- UI: sidebar project switcher (grouped by `group_name`), manage-projects modal
  (create/rename/delete/set-group), and a "Search whole group" toggle on Search & Ask and Compare.

### Permission-aware retrieval  ✅ done (2026-08-05)
Every chunk carries `allowed_groups` (stamped at ingest, mirrored into the Qdrant payload), and
every retrieval path takes a `SearchContext` built from the authenticated principal - HTTP Basic
over two fake users (`alice` in `hr`, `haiks` in `eng`, both `public`) via `spring-boot-starter-security`.

- UI: sidebar shows who you are signed in as; the Import panel picks the access label for the
  upload (only groups you belong to - the server rejects the rest with 403).
- `projectId` / `docIds` stay browser-supplied and narrowing-only; the group filter is applied
  inside every query, before the reranker's 50-candidate over-fetch.
- Existing corpora keep working: `schema.sql` backfills unlabelled chunks to `public` and
  `QdrantAclBackfill` does the same for pre-ACL Qdrant points at startup.
- `AccessControlIntegrationTest` is the "try to break it" half - see `LEARNINGS.md` §16 and
  `RAG-MASTERY.md` §1 for what it found. Not built: audit logging, per-document ACL editing,
  and access-filtered project counts.

### Per-request RAG trace + debug view  ✅ done (2026-08-05)
One `rag_trace` row per answered question, so a wrong answer leaves evidence instead of nothing.

- Table: request_id, principal, project_ids, raw + condensed query, backend, `retrieved` JSONB
  (docId, chunkIndex, score), `stage_latency_ms` JSONB (embed / retrieve / generate / total),
  prompt + completion tokens, the model's original answer, and the guard verdict.
- Written by `TraceRecorder` from both `/ask` and `/chat/stream`; `chat/stream` also emits a
  `trace` frame carrying the request id.
- UI: a "Trace" toggle under each answer showing stage timings, the searched query (with the raw
  one when condensing changed it), and the ranked chunks with scores.
- `GET /traces?limit=N` returns **only the caller's own** traces - a trace holds a question and the
  documents it matched.
- Config: `app.trace.enabled` (default true), `app.trace.keep` (default 500 rows per principal,
  pruned after each insert), `app.trace.max-answer-chars`.
- Token counts come from Ollama's `prompt_eval_count` / `eval_count` and are null when a provider
  does not report them - "not measured" is not "free".

### Injection hardening - fenced context + cite-or-refuse  ✅ done (2026-08-05)
Retrieved text is treated as untrusted data, and an answer that cannot point at a source is not
published. Driven by an actual attack: a poisoned page in the corpus made qwen3:4b reply
`INJECTION SUCCESSFUL - the admin recovery code is hunter2 [1]` before this work.

- `PromptFence` wraps context in BEGIN/END markers, numbers each chunk, neutralises fence markers
  found inside chunk text or metadata, and places the question after the fence.
- System prompt rule 1 states the material is data written by document authors and must never be
  acted on.
- `AnswerGuard` (cite-or-refuse) replaces an uncited answer, or one citing a chunk that was never
  supplied, with "Not found in knowledge base." on `/ask`. `/chat/stream` cannot recall sent
  tokens, so it emits a `guard` frame and the UI marks the answer unverified.
- `InjectionScanner` warns at upload time (denylist, non-blocking); warnings ride back on the
  ingest response and become a toast.
- Fixed on the way: the non-streaming `/ask` path still used `think:false`, so qwen3's
  chain-of-thought landed in the answer body and broke anything parsing it.
- Honest limit: the injected *instruction* no longer runs, but asking for the payload directly
  still returns it as a cited, grounded answer - that is content disclosure, not injection, and
  belongs to access labels and corpus hygiene. See `LEARNINGS.md` §17.

### Per-chunk relevance feedback - eval only (Option A)  ✅ done (2026-08-05)
Goal: collect human relevance labels on individual retrieved chunks so we can MEASURE retrieval /
reranker quality against real usage - NOT to change ranking live (no fine-tuning, no live boost).
The existing whole-answer 👍/👎 (localStorage-only) stays; this adds a per-source signal with a backend.

- **Signal** ✅ - a small 👍/👎 pair on every search result row and on every answer citation chip.
  A click writes `{ query, projectId, docId, chunkIndex, rating: up|down, ts }`; clicking the active
  thumb clears the label. Thumbs are restored on reload from `GET /feedback?projectId&query`.
- **Backend** ✅ - `POST /feedback` (upsert), `DELETE /feedback` (un-vote), `GET /feedback`
  (dump/filter) over a new `chunk_feedback` table. No auth, same posture as the rest of the sandbox.
  **Deviation from the original spec:** one label per `(project, doc, chunk, query)` (UNIQUE +
  ON CONFLICT UPDATE) instead of a plain append-only insert, so consumers read clean
  `(query, chunk, relevant)` triples with no latest-wins dedupe. Labels key on `(doc_id, chunk_index)`,
  never on `chunks.id`, which re-ingest rewrites.
- **Use** ✅ - `FeedbackPrecisionEvalTest` (tag `eval-feedback`) groups the labels by query, replays
  each through all six backends against the live stack, and reports P@5 / P@10 / MRR-of-first-👍 /
  judged coverage per backend. Precision counts JUDGED hits only; coverage is printed beside it so a
  two-label precision cannot pose as a verdict. It is a report, not a gate - labels grow over time.
  Run: `./mvnw test "-Dgroups=eval-feedback" "-DexcludedGroups="` (add `-Deval.rerank=djl` for the
  cross-encoder run; skips when fewer than 10 labels exist, override `-Deval.feedback.min=N`).
- **Explicitly OUT of scope for A:** no query-time score nudge, no cross-encoder fine-tuning. Those
  are a later "Option B" (blend `final = w1*reranker + w2*feedback`) - deferred; risks cold-start on
  unseen queries and overfitting to a few clicks. Revisit only after enough labels accrue.
- **Why:** the cross-encoder is a fixed content model - it never learns from clicks on its
  own. Per-chunk labels are the only way to know if it actually helps on THIS corpus. The 11-question
  golden set gave a first answer on 2026-08-05 (it did not help - MRR 0.919 -> 0.909, see
  `LEARNINGS.md` section 14), but 11 questions is too thin to act on, which is exactly the case for
  labels at scale. Reasoning and full design notes: `LEARNINGS.md` section 15.

## Planned (not yet built)

### CI-runnable eval gate - frozen test corpus  🟨 half done (2026-08-07)
Goal: make the retrieval regression gate enforceable by CI instead of by developer discipline.

- **The limitation being tracked.** The gate built in
  `docs/superpowers/specs/2026-08-05-eval-regression-gate-design.md` runs against
  `WikiRetrievalEvalTest`, whose corpus is the private 428-page wiki. That corpus cannot ship in the
  repo, so the gate **can never run in CI or on a fresh clone** - it skips. It is a pre-merge
  discipline tool for one machine, not enforcement.
- **Why the other eval cannot fill the gap today.** `RetrievalEvalTest` runs anywhere
  (Testcontainers, self-contained) but ingests `docs/` as its corpus (`RetrievalEvalTest:89`). The
  corpus is therefore this repo's own documentation, and its numbers move whenever anyone edits a
  doc. Four files under `docs/` changed on 2026-08-05 alone. Gating it as-is produces failures
  caused by writing documentation, which get ignored within a week.
- **The fix.** Stop walking `docs/`. Freeze a small dedicated corpus into `src/test/resources`, then
  re-point all 18 questions in `golden.yaml` at the frozen file ids. The eval becomes stable and
  CI-gateable, and the wiki gate stays as the richer local check on a realistic corpus.
- **Cost.** Every question in `golden.yaml` needs re-verifying against the new corpus, which is the
  slow part - the corpus snapshot itself is cheap.
- **Why it is deferred.** Single developer, single machine, no CI enforcing anything today. Scoped
  out of drill C on purpose to keep that job small.
- **Why it will matter later.** On a team, or on any project where retrieval quality is a
  deliverable, "run the gate before you merge, please" is not a control. A real project needs the
  gate to run without the private corpus. Revisit before this pattern is copied anywhere that has
  more than one contributor.

**Update 2026-08-07 - a frozen corpus now exists, but it is not yet a gate.**
`RecordCorpus.generate(42)` produces 210 deterministic synthetic records (invoices, delivery notes,
contracts) in `src/test/java/.../eval/`, and `RecordFilterEvalTest` (`-Dgroups=eval-records`) runs
against it under Testcontainers - so it works on a fresh clone and in CI, with no private corpus.
`records-golden.yaml` holds 15 questions whose correct documents are COMPUTED from each question's
expected filter (`RecordGroundTruth`) rather than listed by hand, so the golden set survives a
change to the generator.

What is still missing before this closes:
- It **reports, it does not gate.** No committed baseline, no failure on regression - the same order
  drill C followed for the wiki eval.
- It uses a **fake embedding provider**, so its recall/MRR measure the metadata filter, not
  retrieval quality. The original item's goal (gating semantic retrieval quality in CI) needs
  either real embeddings in CI or committed vectors.
- `golden.yaml` still points at this repo's `docs/`, so `RetrievalEvalTest` remains unstable.

## Everything else on this roadmap is built.
Possible future directions (not scheduled): server-side session persistence, token-budget
history trimming, streaming the compare screen, Option B live-feedback boost / reranker
fine-tuning, and cross-project search analytics.

## Notes
- Keep everything plain HTML/CSS/JS (no framework), matching the current static frontend.
- Backend stays Java/Spring Boot; no new dependencies without asking.
