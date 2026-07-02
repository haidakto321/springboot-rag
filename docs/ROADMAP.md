# Smart Search Roadmap

Feature backlog for the knowledge-base UI, beyond the initial redesign. Ordered by the
agreed build sequence. Each unit is built + verified before the next.

## In progress / planned units

### Unit A - Compare UI  ✅ done (2026-07-02)
Third sidebar screen "Compare backends": one query run through all 5 backends
(fts, pgvector, qdrant, hybrid, rerank) shown side-by-side with per-backend latency and
ranked hits. Frontend-only; reuses the existing `GET /compare` endpoint.

### Unit B - Streaming chat  (next)
Merges "streaming answers" + "conversational follow-up".
- Stream LLM tokens live to the client (SSE endpoint) instead of one blocking JSON
  response - answer types out word-by-word.
- Session memory: keep conversation turns and re-feed prior context to the model so
  follow-up questions ("what about X?") work.
- The Ask screen becomes a chat thread.
- Backend: new streaming endpoint, chat/session handling. Ollama already streams.

**Deferred from Unit B:**
- **Condense-question retrieval** - for vague follow-ups ("tell me more", "why?"),
  rewrite the conversation history + new question into a standalone search query before
  retrieving, instead of retrieving on the latest message alone. Known RAG pattern
  (question condensation). Improves multi-turn retrieval quality. (Med)

### Unit C - Document filter  (after B)
- Scope search / ask / compare to a chosen subset of documents.
- Backend: `doc_id IN (...)` filter across pg (fts + pgvector) and qdrant repos.
- UI: document chips / checkboxes to pick the scope.

## Medium-value backlog (agreed, not yet scheduled)

- **Recent searches / history** - localStorage dropdown of past queries. (Low effort)
- **Result -> open in context** - click a search hit to jump to that document's chunk
  view, scrolled to the chunk. Reuses the existing chunk sub-view. (Low-Med)
- **Pagination / "load more"** - `topK` is hard-locked at 10; add a way to fetch more. (Low)
- **Snippet windowing** - results dump the whole chunk; show the best-matching passage
  window with expand-to-full. (Med)
- **Copy answer / richer citations** - copy button on the answer; citation chips that
  scroll to the cited chunk in its document. (Low)

## Low-value / polish

- Keyboard shortcut (`/` to focus search).
- Search-as-you-type (debounced live search).
- Thumbs up/down relevance feedback.
- Empty-state hint ("no results - try semantic mode").

## Notes
- Keep everything plain HTML/CSS/JS (no framework), matching the current static frontend.
- Backend stays Java/Spring Boot; no new dependencies without asking.
