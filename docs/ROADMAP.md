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

## Planned (not yet built)

### Per-chunk relevance feedback - eval only (Option A)  ⬜ planned
Goal: collect human relevance labels on individual retrieved chunks so we can MEASURE retrieval /
reranker quality against real usage - NOT to change ranking live (no fine-tuning, no live boost).
The existing whole-answer 👍/👎 (localStorage-only) stays; this adds a per-source signal with a backend.

- **Signal:** a thumb on each source/citation chip in Search results and in the Ask answer's chips.
  One click logs `{ query, projectId, docId, chunkIndex, rating: up|down, ts }`.
- **Backend:** `POST /feedback` -> new `chunk_feedback` table (Postgres). Simple insert; no auth
  (single-user dev sandbox, same posture as the rest). Add a `GET /feedback` (or a small eval
  reader) to dump labels for analysis.
- **Use:** offline eval. Feed the labels as `(query, chunkId, relevant)` pairs to check whether the
  reranker ranks thumbs-up chunks above thumbs-down (precision@k on human labels) - complements the
  golden-set eval (`golden.yaml`, `golden-wiki.yaml`). This is the cheap, honest win.
- **Explicitly OUT of scope for A:** no query-time score nudge, no cross-encoder fine-tuning. Those
  are a later "Option B" (blend `final = w1*reranker + w2*feedback`) - deferred; risks cold-start on
  unseen queries and overfitting to a few clicks. Revisit only after enough labels accrue.
- **Why now-ish:** the cross-encoder is a fixed content model - it never learns from clicks on its
  own. Per-chunk labels are the only way to know if it actually helps on THIS corpus. The 11-question
  golden set gave a first answer on 2026-08-05 (it did not help - MRR 0.919 -> 0.909, see
  `LEARNINGS.md` section 14), but 11 questions is too thin to act on, which is exactly the case for
  labels at scale.
- UI note: keep it low-clutter - a small thumb per chip, not a big widget. See LEARNINGS §on
  feedback vs reranking for the reasoning.

### CI-runnable eval gate - frozen test corpus  ⬜ planned (deliberately deferred 2026-08-05)
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

## Everything else on this roadmap is built.
Possible future directions (not scheduled): server-side session persistence, token-budget
history trimming, streaming the compare screen, Option B live-feedback boost / reranker
fine-tuning, and cross-project search analytics.

## Notes
- Keep everything plain HTML/CSS/JS (no framework), matching the current static frontend.
- Backend stays Java/Spring Boot; no new dependencies without asking.
