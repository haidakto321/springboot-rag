# RAG, From Scratch - A Learner's Guide

A teaching notebook written while building this project. It assumes **no prior
Generative-AI knowledge** and explains the *why* and the *theory* behind each piece, then
grounds it in the concrete choices made here. Read top to bottom the first time; use it as a
reference after.

Contents:
1. The problem RAG solves
2. Embeddings & vector search (the core idea)
3. Chunking
4. The three retrieval styles: keyword, vector, hybrid
5. Reranking (the second-stage funnel)
6. What the scores actually mean
7. Generation & grounding (turning chunks into an answer)
8. Chat: memory, context windows, cost
9. Streaming
10. Filtering by document
11. Evaluation (how you know it works)
12. Operational gotchas

---

## 1. The problem RAG solves

A large language model (LLM) like qwen or GPT is a **next-word predictor** trained on a huge
but **frozen** snapshot of text. Two consequences:

- **It doesn't know your private data.** Your README, your company wiki, last week's tickets -
  none of it was in its training set.
- **It hallucinates.** Asked something it doesn't know, it produces fluent, confident, wrong
  text, because its job is to sound plausible, not to be correct.

**RAG = Retrieval-Augmented Generation.** Instead of asking the model to answer from memory,
you first **retrieve** the relevant snippets from *your* documents, paste them into the
prompt, and say "answer using ONLY this." The model becomes a **reasoning + wording engine**
over facts you supply. Benefits: current data, private data, and answers you can **trace back
to a source** (citations).

The whole pipeline:

```
document -> chunk -> embed -> store          (INDEXING, done once at upload)
question -> retrieve top-k chunks -> stuff into prompt -> LLM answers + cites   (QUERY time)
```

Everything below is one stage of that pipeline.

---

## 2. Embeddings & vector search (the core idea)

An **embedding** is a list of numbers (a **vector**, here 768 of them) that represents the
*meaning* of a piece of text. A neural network (here Ollama's `nomic-embed-text`) reads text
and outputs its coordinates in a 768-dimensional "meaning space."

The key property: **texts with similar meaning land close together**, even if they share no
words. "car" and "automobile" end up near each other; "car" and "banana" far apart.

**How "close" is measured - cosine similarity.** Think of each vector as an arrow from the
origin. Two arrows pointing the same direction = similar meaning (cosine ~1). Perpendicular =
unrelated (cosine ~0). Cosine looks at the **angle**, ignoring length, so it compares
*direction* (topic) not *magnitude*. "Cosine distance" = `1 - cosine similarity`; smaller
distance = more similar.

**Vector search** = embed the query, then find the stored chunk vectors with the smallest
distance to it. That's "semantic search": it matches meaning, so a query can find a passage
that answers it even with completely different wording.

- **Weakness:** exact tokens. A part number `INV-5575`, a rare function name, an acronym - these
  carry little "meaning" for the embedder, so semantic search can miss them even though a plain
  text match would nail it. (This is exactly why we ALSO keep keyword search - see section 4.)
- This project stores vectors in TWO engines (Postgres `pgvector` and `Qdrant`) purely to
  compare them; at this scale they return the same results - pick one on ops, not quality.

---

## 3. Chunking

You cannot embed a whole 50-page document as one vector - meaning would be averaged into mush,
and you could not cite a specific passage. So you split documents into **chunks** and embed
each one. Chunking is where a lot of RAG quality is won or lost.

**Why chunk size is a tradeoff:**
- **Too big:** the chunk covers several topics; its single vector is a blurry average, and
  retrieval gets "sort of relevant" hits. You also waste prompt space (and money) stuffing
  irrelevant sentences into the LLM.
- **Too small:** you cut a thought in half; the chunk lacks the context to be understood or to
  answer a question. ("It must be replaced every 6 months" - *what* must?)

**Common strategies (worst to best for structured docs):**
1. **Fixed-size** (every N characters): dead simple, but slices mid-sentence and mid-word.
2. **Sentence / paragraph**: respects language boundaries; better.
3. **Structure-aware** (split on headings): keeps semantically-coherent sections together.
4. **+ Overlap**: let adjacent chunks share a little text so a sentence spanning a boundary
   isn't orphaned - context survives the cut.

**What this project does (and why):** split Markdown on **heading boundaries first**, then
word-window long sections. Crucially, each chunk stores its **heading path** (`# Guide > ##
Setup`) and that path is **embedded together with the body text**. So the vector captures both
*where* the text sits in the document's structure and *what* it says - retrieval can match on
topic hierarchy, not just prose. Code/tables are kept atomic (not split) so they stay valid.

> **Rule of thumb:** chunk on the document's natural seams, keep a chunk to roughly one idea,
> and carry a little metadata (section title, source) alongside the text.

---

## 4. The three retrieval styles: keyword, vector, hybrid

### Keyword / full-text search (FTS)
The classic approach: match the query's **words** against the documents' words, ranked by a
formula like BM25 (Postgres uses `ts_rank`) that rewards rare terms and term frequency.
- **Strength:** exact terms, codes, names, jargon - if the word is there, it's found.
- **Weakness:** no understanding. "unpaid bills past deadline" will NOT find a chunk that says
  "overdue invoices" - different words, same meaning, zero overlap.

### Vector / semantic search
Section 2. Matches **meaning**.
- **Strength:** paraphrase, synonyms, fuzzy intent.
- **Weakness:** exact rare tokens (the `INV-5575` problem).

### Hybrid - use both, then fuse
Keyword and vector have **opposite** strengths and weaknesses, so combining them covers both.
Run both searches, then merge the two ranked lists into one. The merge here is **Reciprocal
Rank Fusion (RRF)**:

```
score(chunk) = Σ over each list   1 / (k + rank_in_that_list)      (k = 60, rank starts at 0)
```

Why it works and why it's clever:
- It uses **rank, not raw score**. Keyword scores and cosine scores live on totally different
  scales and can't be added directly. Rank (1st, 2nd, 3rd...) is comparable across any method.
- A chunk ranked #1 by BOTH methods gets `1/61 + 1/61 ≈ 0.033` - the strongest possible signal.
  A chunk only one method liked gets about half that. So **agreement between methods floats to
  the top.**
- `k=60` (a standard default) softens the difference between top ranks so one method can't
  completely dominate.

**Proven in this project:** query `INV-5575` -> keyword wins, vector misses. Query "unpaid
bills past deadline" -> vector wins, keyword returns nothing. Hybrid gets both right. That single
demo is the whole reason hybrid exists.

---

## 5. Reranking (the second-stage funnel)

Retrieval is a **two-stage funnel**: cheap-but-rough first, expensive-but-precise second.

**Why two stages?** The embedding search uses a **bi-encoder**: the query and each document
were embedded **separately**, in advance. That's what makes it fast (you pre-computed all the
document vectors and just compare arrows). But separate encoding is **approximate** - the model
never looked at the query and a document *together*, so it can't judge subtle relevance.

A **cross-encoder** (the reranker, here `bge-reranker` via DJL) takes the **query and one chunk
together** as a single input and outputs a precise relevance score. Looking at both at once
lets it catch nuance a bi-encoder can't. But it's **slow** - it must run the model once per
(query, chunk) pair, so you can't run it over the whole corpus.

**The funnel:** let hybrid retrieve a wide shortlist (here `candidates = 50`), then let the
cross-encoder **re-score just those 50** and keep the best `topK`. You get near-cross-encoder
quality at near-bi-encoder speed. This is the standard modern retrieval architecture.

- **Recall vs precision:** stage 1 optimizes **recall** (make sure the good chunk is *somewhere*
  in the 50), stage 2 optimizes **precision** (put the truly-best chunk at #1). A reranker can
  only reorder what stage 1 gave it - if retrieval missed the answer entirely, reranking can't
  save you. Retrieve wide, rerank narrow.
- This is why reranking "matters": the LLM only sees the top few chunks. Getting the single most
  relevant chunk into position #1 directly improves answer quality, especially `hit@1`.
- Cost: the cross-encoder downloads a model + native libs (hundreds of MB) and adds latency, so
  it's off by default here (an identity/no-op reranker) and opt-in via config.

---

## 6. What the scores actually mean

**Never compare scores across backends, and don't assume 0-1.** Each is a different kind of
number:

| Backend | Score is... | Range | Higher = better? |
|---------|-------------|-------|------------------|
| `pgvector` / `qdrant` | `1 - cosine_distance` | 0-1 | yes |
| `fts` | Postgres `ts_rank` | small, unbounded | yes |
| `hybrid` | RRF sum `Σ 1/(k+rank)` | ~0.01-0.03 | yes |
| `rerank` | cross-encoder logit | can be negative or large | yes |

- `0.032` from hybrid does NOT mean "3% relevant." It means "ranked ~#1 in both fused lists" -
  which is excellent. RRF numbers are just small by construction.
- **Consequence in the UI:** the score bar normalizes to the max score *within the current
  result set*, because the absolute value carries no cross-query, cross-backend meaning.

---

## 7. Generation & grounding (turning chunks into an answer)

Retrieval hands you the top chunks. Generation turns them into prose. The magic is entirely in
the **prompt contract**:

```
System: Answer using ONLY the numbered context chunks. Cite with [n]. If the answer isn't
        in the context, say exactly "Not found in knowledge base."
User:   Context:
        [1] (README > Setup) <chunk text>
        [2] (guide.md) <chunk text>
        Question: how do I run the tests?
```

- The **"ONLY"** constraint is what suppresses hallucination - it tells the model to defer to the
  supplied facts instead of its own memory. Drop it and the model will happily invent.
- **Numbering** the chunks gives the model handles to cite (`[1]`), which you map back to real
  sources so the user can verify. Traceability is a headline RAG feature - use it.
- The explicit "if not present, say ..." gives the model a safe **out**, so "I don't know" beats
  a confident fabrication.

Grounding quality depends on retrieval: garbage chunks in -> confident garbage out. Most "the RAG
is wrong" bugs are actually **retrieval** bugs, not generation bugs. Debug retrieval first.

---

## 8. Chat: memory, context windows, cost

A single Q&A is stateless. A **chat** remembers prior turns so follow-ups work ("what about
overlap?" only makes sense given the previous turn).

- **What creates "memory":** you resend the previous turns to the model each request. The model
  itself is stateless; the *conversation* lives on the client and is replayed every turn. That's
  the whole trick.
- **Retrieval vs memory are separate.** The model always sees the full (trimmed) history, which
  is what makes it a real chat. Retrieval is a different question: what do you *search* for on a
  follow-up? Retrieving on the latest message alone breaks for vague follow-ups ("tell me more",
  "why is that better?") that have no keywords - you'd search for "why" and get junk.
- **Query condensation** (implemented here) fixes that: on a follow-up turn, a cheap LLM call
  first rewrites {history + new question} into a standalone search query ("why is that better?"
  after a reranker discussion -> "why is a cross-encoder reranker better than bi-encoder search"),
  and retrieval uses THAT. The answer is still generated from the original question + full history.
  First turn skips it (already standalone); a condensation failure falls back to the raw question.
  Toggle: `app.chat.condense-followups`. Cost: one extra short LLM call per follow-up.
- **Context window = the model's finite short-term memory**, measured in tokens (word-pieces).
  Everything - system prompt, retrieved chunks, entire history, and the answer - must fit. Overflow
  and the model silently forgets the oldest content.
- **So you MUST cap history.** Here: send only the last 10 messages (enforced on client AND
  server - never trust the client). Two reasons: stay inside the context window, and, on a paid
  API, you pay **per token every turn** - an unbounded history means every message costs more than
  the last. Turn-count trimming is the simple version; token-budget trimming is the precise one.

---

## 9. Streaming

Without streaming: click Ask, stare at a spinner for 10s, the whole answer appears at once.
With streaming: words appear as the model generates them - same total time, but you read
immediately and can tell it's working. Big perceived-speed win, and it's how every modern
chat UI feels responsive.

- Mechanically: Ollama with `stream:true` returns **newline-delimited JSON**, one object per
  token, the last flagged `done:true`. The server reads that stream and forwards each token.
- Transport here: **NDJSON over a `StreamingResponseBody`** (a POST the browser reads with a
  streaming `fetch`), simpler than SSE when the request carries a body. Frames: `token*` ->
  `sources` -> `done` (or `error`).
- **Gotcha - the response is already "sent" (HTTP 200) the instant streaming starts.** You can't
  return a 500 later, so mid-stream failures must be reported as an in-band `error` FRAME. Do
  cheap validation (empty body) BEFORE the stream opens so those can still be a real 400.
- **Gotcha - async timeout.** Streaming runs on the servlet container's async path, which has a
  default timeout (~30s). A long generation blows past it and the stream is cut with an
  `InterruptedException`. Raise `spring.mvc.async.request-timeout` (this project: 10 min).
- **Gotcha - client disconnect** (user closes tab, or `curl | head` closes the pipe) also surfaces
  as an `InterruptedException` on the server read. That's normal; handle it quietly.

---

## 10. Filtering by document

Sometimes you want to search inside *one* document, not the whole index.

- **Must filter IN the query, not after.** If you fetch the top-k and then drop the wrong docs in
  app code, you get fewer than k from a polluted pool - you've thrown away good hits. Push the
  filter into the database/engine so it selects from the right set to begin with.
- Postgres: `AND doc_id IN (?, ?, ...)`. Qdrant: a `Filter` of OR'd (`should`) `matchKeyword`
  conditions on the `doc_id` payload.
- **UI convention:** "all selected" == "none selected" == no filter (send nothing). Avoids the
  confusing empty-selection dead end.

---

## 11. Evaluation (how you know it works)

"It looks fine when I try it" is not evaluation. RAG has two things to measure, separately:

**Retrieval quality** - did we fetch the right chunks? Build a golden set of
{question -> which chunk is correct}, then compute:
- **recall@k** - is the correct chunk somewhere in the top k? (Did we find it at all?)
- **MRR** (Mean Reciprocal Rank) - `1/rank` of the first correct hit, averaged. Rewards putting
  the right chunk HIGH, not just present.
- **hit@1** - how often the very first result is correct. The strictest, and what reranking moves.

In this project hybrid scored recall@5 = 1.0, MRR = 0.94, hit@1 = 0.89; FTS alone = 0.22 recall -
concrete proof hybrid earns its complexity.

**Answer quality** - is the generated answer faithful to the chunks (no hallucination)? Full human
eval is slow, so use an **LLM-as-judge**: a second model reads the answer + sources and returns
yes/no "is this grounded?". Cheap, repeatable, good as a smoke test (here: 18/18 faithful).

> Lesson: measure retrieval and generation independently. A bad answer is usually a retrieval
> miss wearing a generation costume.

---

## 12. Operational gotchas (hard-won)

- **Testcontainers + Docker 29:** pin the client `api.version=1.44` and Qdrant image `v1.9.0`;
  newer Docker Engines otherwise break the test client. Bites on WSL and native Ubuntu alike
  (both ship Engine 29.x).
- **`think:false` for qwen3:** it's a reasoning model; left on, it emits `<think>` blocks that
  pollute answers and break the judge's parsing. (qwen2.5:7b judged 14/18, qwen3 with thinking
  off judged 18/18.) Know your model's quirks.
- **Embed once per request:** an embedding call is a network round-trip. `/compare` embeds the
  query a SINGLE time and shares the vector across pgvector/qdrant/hybrid so timings reflect
  search cost, not three model calls. Don't re-embed the same text.
- **Strict UTF-8 on upload:** decode with REPORT, not replace, so malformed bytes are a clean 400,
  not silent replacement characters buried in your index.
- **Dev loop:** this app runs via `spring-boot:run` with no devtools - Java changes need a manual
  restart; static files (html/css/js) are picked up live.

### Frontend traps (cost real time here, worth remembering)
- **`[hidden]` loses to `display`:** `.foo { display:flex }` overrides the HTML `hidden`
  attribute. For every element you toggle with `hidden`, add an explicit
  `.foo[hidden]{display:none}`.
- **Escape before you highlight/inject:** build HTML from escaped text only, then wrap matches in
  `<mark>`. Never interpolate raw model/user/content strings into innerHTML.
- **Stream without thrashing the DOM:** keep a handle to the streaming bubble's text node and
  append per token, rather than re-rendering the whole thread on every token.

---

## Where to go next
`docs/ROADMAP.md` lists what's built and what's queued - notably **condense-question retrieval**
(fixes vague follow-ups), snippet windowing, and token-budget history trimming. Each is a small
project you can learn from the same way: read the theory above, then read the diff.
