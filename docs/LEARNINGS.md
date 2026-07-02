# RAG Learnings & Gotchas

A running notebook of what building this project taught about RAG, and things to be
careful about. Grounded in real decisions made here - extend it as you go.

---

## 1. Retrieval

### Scores are NOT comparable across backends
Each backend produces a different KIND of number. Never compare raw scores between them,
and never assume "0-1".

| Backend | Score meaning | Typical range |
|---------|---------------|---------------|
| `pgvector` / `qdrant` | `1 - cosine_distance` | 0-1, higher = closer |
| `fts` (Postgres) | `ts_rank` | small, unbounded |
| `hybrid` | RRF sum `Σ 1/(k+rank)`, k=60 | ~0.01-0.03 |
| `rerank` | cross-encoder logit | can be negative or large |

- **Gotcha:** the UI score *bar* normalizes to the max score IN the current result set,
  precisely because the absolute value is meaningless. Don't render a raw score as a 0-1 bar.
- **RRF intuition:** `2/(60+1) ≈ 0.032` = ranked #1 in BOTH fused lists. RRF only cares about
  RANK, not the underlying score - that's why it fuses keyword + vector cleanly.

### Hybrid and rerank are layered, not separate
- `hybrid` = RRF over [fts results, pgvector results].
- `rerank` = take `hybrid` candidates (a wider `candidates` pool), then re-score the top with a
  cross-encoder. With no reranker configured it degrades gracefully to plain hybrid.
- **Lesson:** retrieve wide, rerank narrow. The reranker is expensive, so only feed it a
  shortlist.

### Semantic search finds meaning, not words
- Keyword/hybrid highlight of query terms works; pure vector search often returns chunks that
  share NO literal words with the query. That's expected, not a bug.
- **Design consequence:** the "highlight terms" toggle is useful for fts/hybrid and misleading
  (by absence) for pure semantic - hence it's toggle-able.

---

## 2. Chunking

- Split on **heading boundaries first**, then apply a word-window over long sections.
- Each chunk stores its **heading path** (`# Guide > ## Setup`) as metadata, and that path is
  **embedded together with the body text** so retrieval matches structure + content.
- Chunk index is 0-based in the DB. (The Ask/chat citations show 1-based `[n]` for humans - be
  deliberate about which one you expose where.)
- **Gotcha:** re-uploading the same filename REPLACES the doc (delete-by-docId then re-insert),
  it does not duplicate. The docId is derived by sanitizing the filename.

---

## 3. Generation / Chat

### Grounding is a prompt contract
- System prompt: "answer using ONLY the numbered context chunks, cite with [n], if not present
  say exactly 'Not found in knowledge base'." Without the "ONLY" constraint the model
  hallucinates from its own training.
- Number the context chunks in the prompt so the model can cite them; map those numbers back to
  real sources for the UI.

### Streaming (Ollama)
- Flip `stream:true` -> Ollama returns **newline-delimited JSON**, one object per token, the last
  with `"done":true`. Read the response body as a stream and parse line by line.
- **Gotcha - InterruptedException:** if the client disconnects mid-stream (e.g. `curl | head`
  closing the pipe), the server's read throws `InterruptedException` / IO error. That's a normal
  client-disconnect, not a server bug - handle it quietly.
- Transport chosen: **NDJSON over `StreamingResponseBody`** (POST + `fetch` body reader), simpler
  than SSE when the request has a body. Frames: `token*` -> `sources` -> `done` (or `error`).
- Response is already committed (HTTP 200) once streaming starts, so mid-stream failures must be
  reported as an in-band `error` FRAME, not an HTTP status. Validate cheap stuff (empty body)
  BEFORE the stream starts so those can still return 400.

### Multi-turn chat
- "Session chat" = the model SEES prior turns. This is independent of how retrieval works.
- **Retrieval on the latest message only still gives real chat** (model has the history), but it
  breaks on vague follow-ups ("tell me more") that have no keywords. Fix = *condense-question
  retrieval* (rewrite history + question into a standalone search query first). Deferred.
- **Cost/context control:** cap the history you send (here: last 10 messages, enforced on BOTH
  client and server - never trust the client). Matters for context-window overflow now, and for
  token cost the moment you move to a paid API.

### `think:false`
- `qwen3` is a reasoning model; leaving thinking on wastes tokens/latency on `<think>` output the
  user never sees. Disable it for a straight answer.

---

## 4. Embeddings

- Embeddings come from Ollama (`nomic-embed-text`, 768-dim). One embed call per query; for
  `/compare` the query is embedded ONCE and the vector shared across pgvector/qdrant/hybrid so the
  timings reflect SEARCH cost, not three model round-trips.
- **Lesson:** embedding is a network round-trip - deduplicate it. Don't embed the same text twice
  in one request.

---

## 5. Evaluation

- Retrieval quality: **recall@k, MRR, hit@1** against a golden set.
- Answer quality: an **LLM-as-judge** faithfulness check (yes/no per answer) - cheaper than human
  eval, good enough as a smoke test.
- **Gotchas learned:** make eval output locale-stable (number formatting differs by locale) and
  use path-based docIds so the golden set is portable.

---

## 6. Ops / Testing

- **Testcontainers + Docker 29:** pin the client `api.version=1.44` (surefire) and Qdrant to a
  known image (`v1.9.0`); newer Docker Engines otherwise break the client. This bites on WSL
  docker-ce AND native Ubuntu (both ship Engine 29.x).
- The cross-encoder reranker downloads a model + native libs (DJL, hundreds of MB) on first run;
  real rerank tests are gated behind an env var so CI/normal test runs stay fast and offline.
- **Strict UTF-8 on upload:** decode with REPORT (not replace) so malformed bytes are a 400 client
  error, not silent replacement characters in the index.
- App runs via `spring-boot:run` with **no devtools** - Java changes need a manual restart; static
  files (html/css/js) are picked up live.

---

## 7. Frontend gotchas (not RAG, but cost real time here)

- **`[hidden]` loses to `display`:** setting `.foo { display:flex }` overrides the HTML `hidden`
  attribute (author rule beats the UA `[hidden]{display:none}`). Add an explicit
  `.foo[hidden]{display:none}` for every element you toggle with `hidden`.
- **Highlight XSS-safety:** escape the text FIRST, then inject `<mark>` - never build HTML from
  raw model/user/content strings.
- Streaming UI: keep a direct handle to the streaming bubble's text node and append per token
  rather than re-rendering the whole thread on every token.

---

## Open items / patterns to try later
See `docs/ROADMAP.md`. Notably: condense-question retrieval, document-scoped filtering,
snippet windowing, and (for paid models) token-based history trimming.
