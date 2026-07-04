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

## Everything on this roadmap is now built.
Possible future directions (not scheduled): server-side session persistence, token-budget
history trimming, streaming the compare screen, a real feedback backend, and cross-project
search analytics.

## Notes
- Keep everything plain HTML/CSS/JS (no framework), matching the current static frontend.
- Backend stays Java/Spring Boot; no new dependencies without asking.
