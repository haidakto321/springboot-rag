# Architecture - what actually happens on a request

This is the "read the machine" document. `README.md` tells you how to run it, `LEARNINGS.md`
explains why each piece exists, `RAG-MASTERY.md` lists what is still missing. This file shows the
**exact path a request takes**, so a developer landing on the repo can see what it can do without
reading the source first.

Nothing here is aspirational - every box maps to a class in `src/main/java`.

Contents:
1. Capability summary
2. Topology
3. `GET /search` - the detailed retrieval path
4. What each backend really does
5. `POST /chat/stream` - full RAG with guard and trace
6. Ingest - what happens to an uploaded document
7. Failure map (what returns which status, and why)
8. Where measurement happens (recall@5, MRR, precision) - and why it is NOT in the request path
9. File map

---

## 1. Capability summary

| Capability | Status | Entry point |
|---|---|---|
| Keyword search (Postgres FTS, `websearch_to_tsquery`) | done | `PgFtsRepository` |
| Vector search (pgvector, cosine HNSW) | done | `PgVectorRepository` |
| Vector search (Qdrant, gRPC) | done | `QdrantRepository` |
| Hybrid fusion (RRF, k=60) | done | `RrfFusion` |
| Cross-encoder rerank (DJL, opt-in) | done, measured | `DjlReranker` |
| Graph expansion (structural + optional entity) | done | `SearchService.graph`, `doc_edge`, `entity` |
| Full RAG answer with citations | done | `AskService` |
| Streaming multi-turn chat + condense-question | done | `ChatService`, `POST /chat/stream` |
| Permission-aware retrieval (access labels) | done | `SearchContext`, `chunks.allowed_groups` |
| Prompt-injection defence (fence + cite-or-refuse) | done | `PromptFence`, `AnswerGuard` |
| Per-request trace + debug view | done | `rag_trace`, `TraceRecorder` |
| Human relevance labels (eval only) | done | `chunk_feedback`, `POST /feedback` |
| Retrieval eval + regression gate (private wiki corpus) | done | `WikiRetrievalEvalTest`, `baseline-wiki.yaml` |
| Query understanding (question -> metadata filter) | done, measured | `QueryUnderstanding`, `FacetCatalogue` |
| Query-understanding eval + gate (runs on a fresh clone) | done | `RecordFilterEvalTest`, `baseline-records.yaml` |
| Incremental re-sync / delete propagation | **missing** | see `RAG-MASTERY.md` section 7 |
| Metrics, alerting, aggregate latency view | **missing** | see `RAG-MASTERY.md` section 6 |

---

### Routing, in one line

`QueryRouter` runs before everything on `/ask` and `/chat/stream`: chit-chat is answered from a
fixed string, "how many X" is answered by one SQL count, and everything else takes the unchanged
RAG path. A router failure resolves to the RAG path, so the worst case is exactly the old
behaviour. `app.route.enabled=false` restores it outright.

## 2. Topology

```mermaid
%%{init: {'theme':'base', 'flowchart': {'curve':'stepAfter'}, 'themeVariables': {'fontSize':'14px', 'fontFamily':'Segoe UI, Arial', 'lineColor':'#64748b', 'edgeLabelBackground':'#ffffff'}}}%%
flowchart LR
    UI["<b>Browser UI</b><br/>static HTML/CSS/JS"]

    subgraph APP["<b>Spring Boot :8085</b>"]
        direction TB
        SEC["<b>Security filter chain</b><br/>HTTP Basic → SearchContext"]
        CTRL["<b>Controllers</b><br/>search · ask · chat · docs<br/>feedback · traces · projects"]
        SVC["<b>Services</b><br/>Ingest · Search · Ask · Chat"]
        GUARD["<b>Guard</b><br/>PromptFence · AnswerGuard<br/>InjectionScanner"]
        TRACE["<b>TraceRecorder</b><br/>one row per answer"]
    end

    subgraph DATA["<b>Stores</b>"]
        direction TB
        PG[("<b>Postgres 5432</b><br/>chunks · doc_edge · entity<br/>chunk_feedback · rag_trace")]
        QD[("<b>Qdrant 6334</b><br/>vectors + payload labels")]
    end

    OLL["<b>Ollama 11434</b><br/>nomic-embed-text · qwen3"]

    UI ==> SEC
    SEC --> CTRL
    CTRL --> SVC
    SVC --> GUARD
    SVC --> TRACE
    SVC --> PG & QD
    SVC -.embed / chat.-> OLL
    TRACE --> PG

    classDef grey stroke:#64748b,fill:#f1f5f9,stroke-width:2px,color:#1e293b
    classDef blue stroke:#3b82f6,fill:#eff6ff,stroke-width:2px,color:#1e3a8a
    classDef purple stroke:#8b5cf6,fill:#f5f3ff,stroke-width:2px,color:#4c1d95
    classDef teal stroke:#14b8a6,fill:#f0fdfa,stroke-width:2px,color:#134e4a
    classDef amber stroke:#f59e0b,fill:#fffbeb,stroke-width:2px,color:#78350f

    class UI grey
    class SEC,GUARD purple
    class CTRL,SVC,PG,QD blue
    class TRACE amber
    class OLL teal

    linkStyle 0 stroke:#16a34a,stroke-width:3px

    style APP fill:#f8fafc,stroke:#cbd5e1,color:#334155
    style DATA fill:#f8fafc,stroke:#cbd5e1,color:#334155
```

---

## 3. `GET /search` - the detailed retrieval path

Every numbered step below is code that runs on every query. The two that people usually forget
are **4** (identity is resolved on the server, never from a parameter) and **7** (the access filter
is inside the SQL, not applied to the result list).

```mermaid
%%{init: {'theme':'base', 'themeVariables': {'fontSize':'13px', 'fontFamily':'Segoe UI, Arial', 'lineColor':'#64748b', 'actorBkg':'#eff6ff', 'actorBorder':'#3b82f6', 'actorTextColor':'#1e293b', 'signalColor':'#64748b', 'signalTextColor':'#1e293b', 'noteBkgColor':'#fff7ed', 'noteTextColor':'#7c2d12'}}}%%
sequenceDiagram
    autonumber
    participant B as Browser
    participant F as Security filter chain
    participant C as SearchController
    participant P as ProjectService
    participant S as SearchService
    participant O as Ollama
    participant DB as Postgres / Qdrant

    B->>F: GET /search?q&type&topK&projectId&group&docIds
    alt no or bad credentials
        F-->>B: 401 Unauthorized
    end
    F->>C: authenticated request
    C->>C: CurrentUser.context() → SearchContext(principal, groups)
    Note over C: groups come from GROUP_* authorities<br/>a request parameter can never add one
    C->>P: resolveScope(projectId, group)
    P-->>C: projectIds (group = every project sharing group_name)
    C->>S: search(ctx, type, q, topK, projectIds, docIds)
    S->>S: validateTopK(1..100)
    alt topK out of range or unknown type
        S-->>B: 400 Bad Request (ProblemDetail)
    end
    opt type != fts
        S->>O: embed(q) via nomic-embed-text (768-dim)
        O-->>S: query vector
    end
    S->>DB: backend query WITH access filter inline
    Note over DB: SQL: allowed_groups && ARRAY[?]::text[]<br/>Qdrant: must(should(matchKeyword(allowed_groups)))<br/>plus project_id IN / doc_id IN when scoped
    DB-->>S: candidate rows (already permission-filtered)
    S->>S: fuse / rerank / expand (see section 4)
    S-->>B: 200 List of SearchHit (id, docId, chunkIndex, content, sourceFile, headingPath, score, updatedAt)
```

**Not traced.** `/search` and `/compare` do not write a `rag_trace` row - tracing covers the two
paths that produce an *answer* (`/ask`, `/chat/stream`), because the fields that make a trace worth
reading (prompt tokens, guard verdict, generated text) only exist there. Search latency is visible
in `/compare` per backend instead.

---

## 4. What each backend really does

`type` selects one of six paths. All six receive the same access filter; they differ in what they
ask the stores for and what they do with the answers.

```mermaid
%%{init: {'theme':'base', 'flowchart': {'curve':'stepAfter'}, 'themeVariables': {'fontSize':'13px', 'fontFamily':'Segoe UI, Arial', 'lineColor':'#64748b', 'edgeLabelBackground':'#ffffff'}}}%%
flowchart TB
    Q["<b>query + SearchContext</b>"] --> T{"type"}

    T -->|fts| F["<b>Postgres FTS</b><br/>websearch_to_tsquery('english')<br/>ORDER BY ts_rank"]
    T -->|pgvector| V["<b>pgvector</b><br/>embedding &lt;=&gt; query::vector<br/>ORDER BY cosine distance"]
    T -->|qdrant| QD["<b>Qdrant</b><br/>gRPC vector search<br/>payload filter"]
    T -->|hybrid| H["<b>FTS + pgvector</b><br/>each at topK"]
    T -->|rerank| R1["<b>hybrid</b> at<br/>app.rerank.candidates (50)"]
    T -->|graph| G1["<b>hybrid</b> at<br/>app.graph.candidates (50)"]

    H --> RRF["<b>RrfFusion k=60</b><br/>score = Σ 1/(60+rank)<br/>tiebreak: best rank, then id"]
    R1 --> RRF2["<b>RrfFusion</b>"] --> RR["<b>Reranker.rerank</b><br/>Identity (default) or<br/>DJL cross-encoder"]
    G1 --> RRF3["<b>RrfFusion</b>"] --> EXP["<b>expand</b><br/>doc_edge neighbours →<br/>chunksByDocIds (filtered)"]
    EXP --> ENT["<b>entity expansion</b><br/>only when edges=semantic|both"]
    ENT --> DEDUP["<b>dedupe by chunk id</b><br/>seed order preserved"] --> RR2["<b>Reranker</b>"] --> TIE["<b>recency tiebreak</b><br/>equal score → newer updated_at"]

    F & V & QD & RRF & RR & TIE --> OUT["<b>trim to topK</b><br/>List&lt;SearchHit&gt;"]

    classDef blue stroke:#3b82f6,fill:#eff6ff,stroke-width:2px,color:#1e3a8a
    classDef purple stroke:#8b5cf6,fill:#f5f3ff,stroke-width:2px,color:#4c1d95
    classDef amber stroke:#f59e0b,fill:#fffbeb,stroke-width:2px,color:#78350f
    classDef grey stroke:#64748b,fill:#f1f5f9,stroke-width:2px,color:#1e293b

    class Q,OUT grey
    class F,V,QD,H,R1,G1 blue
    class RRF,RRF2,RRF3,RR,RR2 purple
    class EXP,ENT,DEDUP,TIE amber
```

Two details that surprise people:

- **`rerank` over-fetches 50 candidates before trimming to `topK`.** The access filter therefore
  has to be *inside* the retrieval query - if it were applied afterwards, the cross-encoder would
  score chunks the caller may not read (`LEARNINGS.md` section 16).
- **Graph expansion walks `doc_edge`, which has no access label.** Content is protected because the
  neighbour's *chunks* are loaded through the filtered query. The edges themselves are readable, so
  graph topology leaks even though content does not.

---

## 5. `POST /chat/stream` - full RAG with guard and trace

This is the path the UI's Ask screen uses. It is the same retrieval as above, wrapped in
condense-question, prompt fencing, a grounding check, and a trace row.

```mermaid
%%{init: {'theme':'base', 'themeVariables': {'fontSize':'13px', 'fontFamily':'Segoe UI, Arial', 'lineColor':'#64748b', 'actorBkg':'#eff6ff', 'actorBorder':'#3b82f6', 'actorTextColor':'#1e293b', 'signalColor':'#64748b', 'signalTextColor':'#1e293b', 'noteBkgColor':'#fff7ed', 'noteTextColor':'#7c2d12'}}}%%
sequenceDiagram
    autonumber
    participant B as Browser
    participant C as ChatController
    participant CS as ChatService
    participant QR as QueryRouter
    participant QU as QueryUnderstanding
    participant S as SearchService
    participant RC as RecordCountRepository
    participant O as Ollama
    participant G as AnswerGuard
    participant T as TraceRecorder

    B->>C: POST /chat/stream {messages[], projectId, group, docIds, think, docType, filters}
    C->>C: reject empty messages (400) · resolveScope · CurrentUser.context()
    Note over C: identity is captured HERE, on the request thread -<br/>the streaming body runs on an async thread where<br/>the SecurityContext is gone
    C->>CS: chatStream(ctx, history, scope, docIds, think, filter, onRoute, onFilter, onToken, onReasoning)
    CS->>CS: trim history to last 10 · require last turn to be a non-empty user message
    CS->>QR: route(raw question)
    Note over QR: rules first (blank, fixed greetings) then one<br/>schema-constrained call. Never throws: any failure is SEARCH
    QR-->>CS: chitchat | aggregate | search
    CS-->>B: {"type":"route","route":"..."} FIRST frame of all
    alt route = chitchat
        CS-->>B: canned reply · trace(route=chitchat) · done
        Note over CS: no condensing, no extraction, no retrieval,<br/>no generation - three model calls skipped
    end
    opt follow-up turn and app.chat.condense-followups
        CS->>O: condense(conversation + follow-up) → standalone query
        O-->>CS: retrieval query (falls back to the raw question on failure)
    end
    opt no caller filter and app.understand.enabled
        CS->>QU: extract(ctx, scope, RAW question)
        Note over QU: raw, not condensed - condensation drops<br/>the entity the filter needs
        QU->>QU: FacetCatalogue.forProjects (cached, under the caller's labels)
        QU->>O: one call: facet list + question → filter JSON
        O-->>QU: JSON (or prose, or nothing)
        QU->>QU: ExtractionValidator - rebuild through MetadataFilter.parse,<br/>drop unknown paths / docTypes / malformed conditions
        QU-->>CS: filter + latency + dropped[] (NEVER throws)
    end
    alt route = aggregate
        CS->>RC: count(ctx, scope, filter) - COUNT(DISTINCT doc_id) under the caller's labels
        RC-->>CS: n
        CS-->>B: "n invoice records match where ..." · trace(route=aggregate) · done
        Note over CS: the number is written by CODE, never by the model,<br/>and this route NEVER widens: 0 is a correct count
    end
    CS->>S: searchTraced(ctx, "rerank", retrievalQuery, contextChunks, filter)
    S-->>CS: hits + {embed, retrieve} ms
    alt filtered result is empty and the filter was not
        CS->>S: searchTraced(... MetadataFilter.none())
        Note over CS: widen - a wrong filter costs one extra query,<br/>not a confident "not found"
    end
    CS-->>B: {"type":"filter","applied":{...},"widened":bool} BEFORE any token
    alt no hits
        CS-->>B: token "No relevant chunks found" · trace(guard=no-hits)
    end
    CS->>CS: PromptFence.buildUserPrompt - BEGIN/END markers, numbered chunks,<br/>fence markers inside content neutralised, question placed AFTER the fence
    CS->>O: chatStream(system rules + fenced context, think:true)
    loop per delta
        O-->>CS: reasoning delta → onReasoning (forwarded only when think=true)
        O-->>CS: content delta → onToken
        CS-->>B: {"type":"token"} / {"type":"reasoning"}
    end
    O-->>CS: final chunk (prompt_eval_count, eval_count)
    CS->>G: check(full answer, chunkCount)
    G-->>CS: verdict cited | ungrounded | bad-citation
    CS->>T: record(requestId, principal, raw + condensed query, retrieved[], stage ms,<br/>tokens, answer, verdict, applied filter, widened, route)
    T->>T: INSERT rag_trace, prune to app.trace.keep per principal
    CS-->>B: {"type":"sources"} · {"type":"trace"} · optional {"type":"guard"} · {"type":"done"}
    Note over B: tokens are already rendered, so a failed guard can only<br/>annotate the answer - the UI shows a red "unverified" banner
```

`GET /ask` is the same pipeline without streaming, and there the guard **replaces** a failed answer
with `Not found in knowledge base.` - it can, because nothing has been sent yet.

---

## 6. Ingest - what happens to an uploaded document

```mermaid
%%{init: {'theme':'base', 'flowchart': {'curve':'stepAfter'}, 'themeVariables': {'fontSize':'13px', 'fontFamily':'Segoe UI, Arial', 'lineColor':'#64748b', 'edgeLabelBackground':'#ffffff'}}}%%
flowchart TB
    UP["<b>POST /projects/{id}/documents</b><br/>multipart .md + groups[]"] --> V1{"<b>validate</b><br/>.md · ≤2 MB · strict UTF-8"}
    V1 -->|fail| E400["<b>400</b> ProblemDetail"]
    V1 --> V2{"<b>groups ⊆ your groups?</b>"}
    V2 -->|no| E403["<b>403</b> AccessDenied"]
    V2 --> V3{"<b>groups known?</b>"}
    V3 -->|no| E400
    V3 --> SCAN["<b>InjectionScanner</b><br/>denylist → warnings (never blocks)"]
    SCAN --> CH["<b>MarkdownChunker</b><br/>split by heading, breadcrumbs kept,<br/>code blocks and tables atomic"]
    CH --> SEC{"<b>SecretScanner</b><br/>inside IngestService.ingestChunks,<br/>the funnel EVERY ingest path crosses"}
    SEC -->|credential found| QUAR["<b>quarantine table</b><br/>un-index first, then hold.<br/>never reaches chunks / Qdrant / registry"]
    QUAR --> AUD[("<b>quarantine_audit</b><br/>held · release · discard<br/>outlives the pen row. no raw text")]
    SEC -->|clean| CAP["<b>capToBudget</b><br/>hard 2000-char cap so a chunk<br/>fits nomic-embed-text's context"]
    CAP --> DEL["<b>delete existing chunks</b><br/>upsert-by-(project, docId)"]
    DEL --> EMB["<b>embed each chunk</b><br/>Ollama nomic-embed-text"]
    EMB --> W1[("<b>Postgres</b><br/>chunks + allowed_groups")]
    EMB --> W2[("<b>Qdrant</b><br/>vector + payload label")]
    W1 --> EDGE["<b>WikiLinkParser</b><br/>outbound links → doc_edge"]
    EDGE --> ENT{"<b>edges=semantic|both?</b>"}
    ENT -->|yes, one LLM call PER CHUNK| EX["<b>EntityExtractor</b><br/>entity · chunk_entity · entity_edge"]
    ENT -->|no, the default| DONE["<b>IngestResponse</b><br/>docId · chunksStored · warnings[]"]
    EX --> DONE

    classDef blue stroke:#3b82f6,fill:#eff6ff,stroke-width:2px,color:#1e3a8a
    classDef purple stroke:#8b5cf6,fill:#f5f3ff,stroke-width:2px,color:#4c1d95
    classDef amber stroke:#f59e0b,fill:#fffbeb,stroke-width:2px,color:#78350f
    classDef red stroke:#dc2626,fill:#fef2f2,stroke-width:2px,color:#7f1d1d
    classDef grey stroke:#64748b,fill:#f1f5f9,stroke-width:2px,color:#1e293b

    class UP,DONE grey
    class V1,V2,V3 purple
    class E400,E403 red
    class SCAN,CH,CAP,DEL amber
    class EMB,W1,W2,EDGE,EX,ENT blue
```

**Known gap:** *markdown* ingest is one-shot. Nothing detects that an uploaded page changed, and
deleting a page upstream leaves it in the index (`RAG-MASTERY.md` section 7). Record ingest, below,
does not have this gap.

---

## 6b. Record ingest - extracted JSON instead of markdown

`POST /projects/{id}/records` is the path for an upstream pipeline that already did
upload -> parse -> extraction. The input is arbitrary nested JSON whose schema differs per document
type and per tenant, so nothing here requires a schema up front.

```mermaid
%%{init: {'theme':'base', 'flowchart': {'curve':'stepAfter'}, 'themeVariables': {'fontSize':'13px', 'fontFamily':'Segoe UI, Arial', 'lineColor':'#64748b', 'edgeLabelBackground':'#ffffff'}}}%%
flowchart TB
    RQ["<b>POST /projects/{id}/records</b><br/>docId · docType · record · groups[]"] --> RV{"<b>validate</b><br/>docId · docType · object"}
    RV -->|fail| RE400["<b>400</b> ProblemDetail"]
    RV --> PROF{"<b>render_profile</b><br/>for this docType?"}
    PROF -->|yes| REND
    PROF -->|no, the common case| REND["<b>RecordRenderer</b><br/>header · section · array-element blocks<br/>breadcrumb = JSON path"]
    REND --> UW["<b>ValueWrapper</b><br/>value → text<br/>confidence · page · bbox → metadata"]
    UW --> EMPTY{"<b>any text?</b>"}
    EMPTY -->|no| RE400
    EMPTY --> HASH["<b>RecordHash</b><br/>content_hash = rendered text<br/>raw_hash = raw record"]
    HASH --> DEC{"<b>compare with</b><br/>document registry"}
    DEC -->|all equal| SKIP["<b>skipped</b><br/>zero embedding calls"]
    DEC -->|text same, raw differs| REFR["<b>metadata-refreshed</b><br/>UPDATE payload in both stores"]
    DEC -->|text · model · profile · groups differ| ING["<b>indexed</b><br/>capToBudget → embed → write"]
    ING --> W1[("<b>Postgres</b><br/>chunks + doc_type + metadata")]
    ING --> W2[("<b>Qdrant</b><br/>vector + nested payload")]
    W1 --> REG[("<b>document</b><br/>registry row")]
    W2 --> REG

    classDef blue stroke:#3b82f6,fill:#eff6ff,stroke-width:2px,color:#1e3a8a
    classDef purple stroke:#8b5cf6,fill:#f5f3ff,stroke-width:2px,color:#4c1d95
    classDef amber stroke:#f59e0b,fill:#fffbeb,stroke-width:2px,color:#78350f
    classDef red stroke:#dc2626,fill:#fef2f2,stroke-width:2px,color:#7f1d1d
    classDef grey stroke:#64748b,fill:#f1f5f9,stroke-width:2px,color:#1e293b

    class RQ,SKIP,REFR grey
    class RV,PROF,EMPTY,DEC purple
    class RE400 red
    class REND,UW,HASH amber
    class ING,W1,W2,REG blue
```

**Chunk metadata** is three nested trees per chunk, stored in `chunks.metadata` (JSONB) and mirrored
into the Qdrant payload:

| Tree | Holds | Example filter path |
|---|---|---|
| `values` | the extracted data, nested by its path in the record | `values.customer.name` |
| `prov` | what the extractor said about it: `confidence`, `page`, `bbox`, `span` | `prov.customer.confidence` |
| `conf` | per-chunk aggregate over numeric confidences | `conf.min` |

Nested rather than flat dotted keys because Qdrant parses a dot inside a payload key as a path
separator - a flat `"customer.name"` key would match in Postgres and never in Qdrant.

**Delete** (`DELETE /projects/{id}/records/{docId}`, and `IngestService.delete` generally) removes:
Qdrant points **first** (the fallible store, so a failure is retryable), then Postgres chunks,
`doc_edge` rows in **both** directions, the `document` registry row, and orphaned entities.
`chunk_feedback` and `rag_trace` rows are kept on purpose - labels are eval evidence and traces are
a record of what was actually answered.

### Where a metadata filter is enforced

| Backend | Enforcement point |
|---|---|
| `fts`, `pgvector` | `FilterSql` fragment appended to the WHERE clause of the retrieval query |
| `qdrant` | `FilterQdrant` conditions added to the search request's `must` / `must_not` |
| `hybrid` | both arms filtered before RRF fusion |
| `rerank` | the **over-fetch** is filtered, before the trim to topK |
| `graph` | seed filtered, and the neighbour chunk load filtered too, so expansion cannot bypass it |

A filter narrows only. `allowed_groups` stays a separate AND term in every one of those queries: a
filter is a caller preference, a label is a boundary.

---

## 7. Failure map

| Situation | Response | Where |
|---|---|---|
| No / wrong credentials | `401` | Spring Security filter chain |
| Authenticated but no groups, or anonymous principal | `403` | `CurrentUser.context()` |
| Labelling a document with a group you are not in | `403` | `CurrentUser.requireOwnGroups` |
| Unknown `type`, `topK` outside 1..100, blank docId, unknown group, bad rating, oversized query | `400` ProblemDetail | `GlobalExceptionHandler` |
| Upload not `.md`, over 2 MB, or invalid UTF-8 | `400` | `DocumentController.parseUpload` |
| Record with no docType, non-object record, or one that renders to no text | `400` | `RecordIngestService.ingest` |
| Document carries credential-shaped text | `200` with `quarantined: true`, nothing indexed | `IngestService.ingestChunks` throws `QuarantineRequiredException`; the caller holds it via `QuarantineService` |
| Release requested for a document you cannot read | `400` | `QuarantineController.require` - the lookup goes through your groups |
| Release or discard by a caller without the role | `403` | `@PreAuthorize("hasRole('quarantine-release')")`; the group lookup above still applies on top |
| Streamed answer never cites a supplied chunk | nothing streamed; `AnswerGuard.REFUSAL` sent instead | `GuardedEmitter` (HOLDING state at end of stream) |
| Streamed answer cites out of range mid-answer | stream stops after the good prefix, `guard` frame | `GuardedEmitter` (PASSING state) |
| Groundedness judge unreachable or unparseable | answer allowed | `GroundednessJudge.judge` - a judge outage must not refuse everything |
| Malformed filter JSON, unknown op, `range` with no bound, `in` with an empty list, illegal path segment | `400` | `MetadataFilter.parse` / `FilterSql.segments` |
| Filter path that does not exist in any record | matches nothing (not an error - schemas differ per tenant) | `FilterSql` |
| `date` range filter on the `qdrant` backend | `400` - Qdrant `Range` is numeric only | `FilterQdrant.numericRange` |
| Feedback on a chunk you cannot read | `400` (deliberately identical to "not found") | `FeedbackController.requireVisible` |
| Ollama down / model missing | `503` | `ChatUnavailableException` |
| Qdrant down at startup | app still boots, Qdrant backends fail per call | `QdrantRepository.ensureCollection` |
| Qdrant down during search | `500` | `SearchService.qdrantSearch` |
| Answer with no citation or a fabricated one | `/ask`: replaced by refusal. `/chat/stream`: `guard` frame + UI banner | `AnswerGuard` |
| Trace insert fails | logged, request unaffected | `TraceRecorder` |
| Query understanding: model down, timeout, or garbage output | answer proceeds UNFILTERED, reason recorded in `dropped[]` | `QueryUnderstanding.extract` |
| Query understanding: model invents a path or docType | that condition is dropped, the rest survive | `ExtractionValidator` |
| Facet catalogue query fails | empty catalogue → no extraction → unfiltered answer | `FacetCatalogue.forProjects` |
| Extracted filter matches nothing | retrieval retried unfiltered, `widened: true` reported | `AskService` / `ChatService` |

---

## 8. Where measurement happens

A point of confusion worth stating plainly: **recall@5, MRR and hit@1 are not computed at query
time.** No request calculates them - they need a golden set of known-correct answers, so they live
in tests that replay questions against the corpus.

| Metric | Computed in | Against | Gate? |
|---|---|---|---|
| recall@5, MRR, hit@1 (self corpus) | `RetrievalEvalTest` (`-Dgroups=eval`) | this repo's `docs/` | report |
| recall@5, MRR, hit@1 (real corpus) | `WikiRetrievalEvalTest` (`-Dgroups=eval-wiki`) | live `docmaster` project, `golden-wiki.yaml` | **yes** - fails on a >0.02 drop vs `baseline-wiki.yaml` |
| Faithfulness (LLM judge) | `FaithfulnessEvalTest` (`-Dgroups=eval-judge`) | generated answers | report |
| precision@5/@10, MRR of first 👍 | `FeedbackPrecisionEvalTest` (`-Dgroups=eval-feedback`) | human labels in `chunk_feedback` | report |
| Extraction precision/recall, docType accuracy, widen rate, recall@5/MRR with vs without | `RecordFilterEvalTest` (`-Dgroups=eval-records`) | committed synthetic corpus (`RecordCorpus.generate(42)`) + `records-golden.yaml` | **yes** - fails on a >0.05 drop vs `baseline-records.yaml`, or on a lost filter / new over-extraction |
| Per-request latency and tokens | `rag_trace`, written live | every answer | none (no alerting) |

The metrics that DO exist per request are latency per stage and token counts, in the trace.

---

## 9. File map

| Concern | Class |
|---|---|
| Identity → access labels | `security/CurrentUser`, `security/SearchContext`, `security/SecurityConfig` |
| ACL migration for old data | `schema.sql` backfill, `security/QdrantAclBackfill` |
| Retrieval orchestration | `service/SearchService` (`search`, `searchTraced`, `compare`) |
| Backends | `repository/PgFtsRepository`, `PgVectorRepository`, `QdrantRepository` |
| Fusion / rerank | `fusion/RrfFusion`, `rerank/IdentityReranker`, `rerank/DjlReranker` |
| Graph | `repository/DocEdgeRepository`, `repository/EntityRepository`, `graph/*` |
| RAG answers | `service/AskService`, `service/ChatService` |
| Query understanding | `understand/FacetCatalogue`, `understand/ExtractionValidator`, `understand/QueryUnderstanding`, `understand/FilterJson`, `repository/FacetRepository`, `web/FacetController` |
| Injection defence | `guard/PromptFence`, `guard/AnswerGuard`, `guard/InjectionScanner` |
| Tracing | `trace/RagTrace`, `trace/TraceRecorder`, `trace/TraceRepository`, `web/TraceController` |
| Human labels | `repository/FeedbackRepository`, `web/FeedbackController` |
| Ingest | `service/IngestService`, `chunk/MarkdownChunker`, `tool/WikiImporter` |
| Schema | `src/main/resources/schema.sql` (idempotent, runs at startup) |
| Frontend | `src/main/resources/static/{index.html,app.js,style.css}` - no framework |

> Rendering note: the `%%{init: ...}%%` blocks above render correctly on GitHub and with
> `mermaid-cli`, but the bundled VS Code "Markdown Preview Mermaid Support" extension shows a blank
> box for any diagram carrying a config directive. Diagrams look right on GitHub; locally the
> arrows will curve.
