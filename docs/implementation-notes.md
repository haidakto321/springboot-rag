# Implementation notes - springboot-rag

Running log of decisions, deviations from the plan, and tradeoffs.

## 2026-06-13 - Scaffold + toolchain (done inline by controller)

Environment had **no Maven** and **no Ollama** installed; Java 25 (not 21); Docker present.
Resolved before any code tasks:

- **Build tool:** added a **Maven Wrapper** (`./mvnw`, `only-script`, downloads Maven
  3.9.16 on first run). No system Maven install needed. **Build command is `./mvnw`,
  not `mvn`** - all plan steps that said `mvn` use `./mvnw`.
- **Spring Boot 3.3.5 -> 3.5.6.** start.spring.io requires Boot >= 3.5.0, and 3.5.x also
  tolerates JDK 25 better. Scaffold pulled from start.spring.io (gives wrapper + base).
- **Java:** runtime JDK 25, compile `release` target 21 (`java.version=21`).
- **Qdrant client 1.12.x -> 1.15.0.** 1.12.1 does not exist on Central; 1.15.0 is stable
  (latest at build time was 1.18.1).
- **springdoc 2.6.0 -> 2.8.4** for Boot 3.5 compatibility.
- **testcontainers** versions managed by the Boot parent BOM (no explicit versions).
- **application.properties -> application.yml** (matches plan config layout).
- Removed the default `SpringbootRagApplicationTests` (its `contextLoads` needs a running
  DB; the Task 14 Testcontainers integration test replaces it).

Verified: `./mvnw dependency:resolve` and `./mvnw compile` both succeed.

## Code tasks

Executed via subagent-driven development (implementer + spec review + quality review per task).
Notes per task appended below as they complete.

**Review-process adaptation:** plan tasks grouped into 6 cohesive implementer units
(files that change together). Controller verifies the build/tests after each unit;
substantive units (repositories, services, integration) get a subagent review, plus a
final whole-project review. Trivial transcribe-from-plan units (pure utils) are
controller-verified.

### unit-1 core utils (DONE)
SearchHit, Chunker (+test), RrfFusion (+test). 6 tests pass.
- **Deviation:** plan's `RrfFusion` test asserted an order for tied RRF scores. Added a
  deterministic tiebreaker (best/lowest rank, then id descending) so ties resolve
  predictably. Reasonable real-world tiebreak; design unaffected (still RRF k=60).

### unit-2 embedding (DONE)
EmbeddingProperties, EmbeddingProvider, OllamaEmbeddingProvider (+MockWebServer test),
EmbeddingConfig, `@EnableConfigurationProperties` on the app class. 1 test passes, compiles.
No deviations.

### unit-3 repositories (DONE, compile-verified)
PgVectorRepository, PgFtsRepository, QdrantConfig, QdrantRepository. Compiles.
- **Deviation:** `ValueFactory` has no `int` overload -> `value((long) chunkIndex)`.
- **Deviation:** Qdrant client 1.15.0 declares its transitive deps as `runtime`, so Maven
  did not put them on the compile classpath. Added explicit compile deps in pom.xml:
  `io.grpc:grpc-api:1.65.1`, `com.google.guava:guava:32.1.3-android`,
  `com.google.protobuf:protobuf-java:3.25.1` (versions from the client's own dep tree).
- Behavior (live Qdrant/Postgres) verified later by the Testcontainers integration test.

### unit-4 services (DONE) - IngestService, SearchService. Compiles. No deviations.
### unit-5 web (DONE) - IngestRequest/IngestResponse, IngestController, SearchController. Compiles. No deviations.

### unit-6 integration test (DONE, written; see env note)
SearchIntegrationTest (Testcontainers: pgvector + qdrant + fake embeddings).
- **Plan-bug fixed:** the plan set `app.embedding.dimension=3`, but `schema.sql` hardcodes
  `vector(768)` -> a 3-dim insert fails. Fixed: fake embeddings emit 768-dim vectors and
  the dimension is left at its 768 default. (768 also matches the real `nomic-embed-text`.)

## Testcontainers / Docker 29 fix (RESOLVED)

Docker Desktop here is Engine **29.4.0 / API 1.54**. Two problems, both fixed:

1. **docker-java returned HTTP 400 on `/info`** (Testcontainers couldn't connect), even
   though the `docker` CLI worked. Root cause: docker-java defaults to an API version the
   new engine rejects. **Fix:** pin docker-java's `api.version` system property to `1.44`
   via the surefire `systemPropertyVariables` in `pom.xml`. (Note: docker-java reads the
   `api.version` SYSTEM PROPERTY, not the `DOCKER_API_VERSION` env var - the env var had no
   effect.) Also bumped docker-java to **3.7.1** via `dependencyManagement` (Testcontainers
   1.21.3 ships 3.4.2). The `api.version` pin is the decisive fix.
   - Also fixed a stale 2022 `~/.testcontainers.properties` that pinned
     `NpipeSocketClientProviderStrategy` at the old `docker_engine` pipe (current Desktop
     serves `dockerDesktopLinuxEngine`). Now uses
     `EnvironmentAndSystemPropertyClientProviderStrategy` + `docker.host` =
     `npipe:////./pipe/dockerDesktopLinuxEngine`. Backup:
     `~/.testcontainers.properties.bak-2026-06-13`.
2. **Qdrant `:latest` image would not finish pulling** (repeated CloudFront EOF on its large
   layers). **Fix:** pinned the IT to `qdrant/qdrant:v1.9.0` (smaller, pulled cleanly).
   Qdrant Java client 1.15.0 works fine against server v1.9.0 for create/upsert/search/delete.
   (`docker-compose.yml` still uses `qdrant/qdrant:latest` for the app; change if needed.)

**Result:** `./mvnw test` now runs the FULL suite green, including `SearchIntegrationTest`
which starts pgvector + Qdrant containers and exercises ingest + all 4 search backends end
to end. The Qdrant repository is now verified LIVE.

## Verification status

- **Full project compiles** (Java 25, Boot 3.5.6).
- **7 unit tests pass:** ChunkerTest(3), RrfFusionTest(3), OllamaEmbeddingProviderTest(1).
- **pgvector + FTS + RRF verified LIVE** against a real `pgvector/pgvector:pg16` container
  (started via `docker compose up -d postgres`) using a throwaway direct-JDBC test
  (now removed): insert with `?::vector`, cosine `<=>` ordering, `ts_rank` FTS, RRF hybrid,
  and delete-by-doc all confirmed working.
- **Qdrant repository: verified LIVE** via `SearchIntegrationTest` (Testcontainers) once the
  Docker 29 fix above was applied - create collection, upsert, vector search, delete all work.
- `docker compose` Postgres container left running on :5432 (stop with `docker compose down`).

## Final code review (subagent) - outcome

Verdict: sound for a self-study sandbox; core mechanics correct.

**Applied fix:** `QdrantRepository.ensureCollection()` was `@PostConstruct` throwing checked
exceptions -> if Qdrant was down at startup the WHOLE Spring context failed to load (couldn't
even use FTS/pgvector). Now it catches and logs a warning, so the app boots without Qdrant.

**Known limitations (left as-is for a sandbox; documented, not fixed - YAGNI):**
- Partial-write consistency: `IngestService` commits each chunk to Postgres then Qdrant; if
  Qdrant fails mid-loop, Postgres has rows Qdrant lacks. Acceptable for a sandbox.
- `QdrantRepository.search` reads payload keys without null-checks (NPE if a point lacks them).
- No `topK` upper bound; `IngestRequest` not bean-validated (blank docId -> 500 not 400).
- `/compare` re-embeds the query per backend, so each backend's timing includes the Ollama
  round-trip (fine for relative comparison; noted).
- `spring.sql.init.mode=always` re-runs idempotent `schema.sql` each boot.

These are good "next improvement" exercises for the study project.

## Live `/compare` lessons (real Ollama `nomic-embed-text`, 768d)

Corpus: ACME accounts-payable docs (`common_doc2`, `common_doc2_part2`) - invoice tables with codes INV-55xx, vendors, statuses [OVERDUE]/[PENDING]/[APPROVED], AP policy text.

### Lesson 1: exact code -> FTS wins, vector fails
Query `INV-5575`:
- **fts**: top hits id=3, id=6 - both chunks actually contain `INV-5575`. Correct. (8ms)
- **pgvector / qdrant**: top hit id=17 = a chunk about `INV-5518`, does NOT contain 5575. Embeddings see "invoice table text" as all semantically alike; codes carry no meaning. Confident score (~0.53) but wrong.
- **hybrid**: id=6 (contains 5575) floats to top - ranked high in BOTH lists, RRF agreement rescues it.
- Takeaway: exact IDs/codes/SKUs are a keyword/FTS job. Vectors are blind to literal tokens.

### Lesson 2: paraphrase/synonym -> vector wins, FTS empty
Query `unpaid bills past deadline` (none of those words appear literally; docs say "overdue", "due date", "late fee"):
- **fts**: `[]` EMPTY (3ms). No token match -> no result. Keyword search cannot bridge synonyms.
- **pgvector / qdrant**: id=9 (Late Payment & Dispute policy), id=3 (OVERDUE invoice rows), id=4 (aging "Overdue past due date"). Semantic match. Correct.
- **hybrid**: equals vector (FTS contributed nothing).
- Takeaway: meaning/paraphrase queries are a vector job. Hybrid = safety net covering both.

### Score scales differ on purpose
FTS `ts_rank` ~0.10, vector cosine-similarity ~0.53, RRF ~0.03. Different scales, not comparable. That is exactly why RRF fuses by **rank position**, not raw score.

### How each search reaches the data (index mechanism)
- **FTS**: GIN inverted index (`idx_chunks_tsv`) - word -> chunk-id postings. Lookup jumps straight to chunks with the token. No full scan. Fast + exact, but only literal tokens.
- **pgvector / qdrant**: HNSW graph index (Approximate Nearest Neighbor). Search navigates neighbor-to-neighbor toward the query vector, visits a small subset, skips most. O(log N), approximate. On tiny corpora (~10 chunks) it effectively visits all; the win shows at scale (millions).

## DB columns used per search approach

Postgres `chunks` table columns: `id`, `doc_id`, `chunk_index`, `content`, `tsv`, `embedding`, `created_at`.

| Approach | Store | Column(s) searched | Index | Query op | Columns returned |
|----------|-------|--------------------|-------|----------|------------------|
| **fts** | Postgres | `tsv` (tsvector, GENERATED from `content`) | `idx_chunks_tsv` GIN | `tsv @@ plainto_tsquery('english', q)`, rank `ts_rank(tsv, ...)` | `id, doc_id, chunk_index, content` |
| **pgvector** | Postgres | `embedding` vector(768) | `idx_chunks_embedding` HNSW cosine | `embedding <=> q::vector` (cosine distance, sort ASC) | `id, doc_id, chunk_index, content` |
| **qdrant** | Qdrant (not Postgres) | vector in collection `chunks` | HNSW (Qdrant internal) | cosine search over vectors | from payload `{doc_id, chunk_index, content}`; point id = Postgres `id` |
| **hybrid** | Postgres only | `tsv` + `embedding` | both GIN + HNSW | run fts + pgvector, fuse with RRF k=60 | `id, doc_id, chunk_index, content` |

Notes:
- `tsv` is never written directly - Postgres generates it from `content` on insert (`GENERATED ALWAYS AS (to_tsvector('english', content)) STORED`).
- `embedding` is written by `IngestService` (Ollama vector) as a `?::vector` literal.
- Qdrant stores its OWN copy of the vector + a payload duplicate of `doc_id/chunk_index/content`; Postgres `content`/`tsv` columns are not touched by the qdrant path. Point id reuses the Postgres row id so results line up across backends.
- `doc_id`, `chunk_index`, `content` are output/payload columns (returned), not the searched column - except `content` indirectly feeds `tsv`.
- `created_at` unused by search.

## RRF (Reciprocal Rank Fusion) explained

Hybrid merges the FTS list + the pgvector list into one, using **rank position only**, not raw score.

Per document: `score(doc) = sum over each list of 1 / (k + rank)`, with `rank` = 0-based position, `k` = 60.

Worked example (INV-5575, k=60): id=6 sat at rank 1 in BOTH lists -> `1/(60+1) + 1/(60+1) = 0.0328` -> top. Matches the live hybrid output (id=6 score 0.0322). A doc in only one list gets a single term -> ranks lower. Agreement across keyword + vector is what wins.

Why rank, not score: FTS `ts_rank` ~0.10 and vector cosine ~0.53 live on different scales and cannot be summed directly. Rank is universal (1st is 1st in any list), so RRF sidesteps score normalization entirely. That robustness is why RRF (k=60) is the industry default.

## qdrant vs pgvector: why they tie here

In both live experiments, qdrant and pgvector returned the same hits, same order, near-identical scores. Expected, because both store the SAME Ollama 768d vectors and both run HNSW + cosine -> same math -> same answer. No quality difference at this scale.

Qdrant's value is operational, not better ranking: dedicated vector DB, rich payload filtering, horizontal sharding/quantization/distributed scaling for huge vector volumes. pgvector's value: vectors live next to relational data, one DB to operate, SQL filtering. For a 10-chunk sandbox there is no visible difference; a real difference would only show at millions of vectors with heavy filtering + concurrency (out of scope here). The exercise value was building both behind one `EmbeddingProvider` + repository split so swapping costs one class.

Lesson: choose pgvector-vs-qdrant by ops/scale needs, not by result quality at small scale.

## Lesson 3: mixed code+concept query exposed plainto_tsquery AND-trap

Query `INV-5518 dispute resolution late payment` (exact code + concept in one):
- **fts**: `[]` EMPTY. `plainto_tsquery` AND-joins every term -> `inv & 5518 & disput & resolut & late & payment`. The code lives in invoice-table chunks, the concept words in policy chunks; NO single chunk holds all six -> zero match. The more topics you AND, the easier to match nothing. FTS is brittle for multi-topic queries.
- **pgvector / qdrant**: handled gracefully. Embeds the whole query, ranks by overall similarity. Top id=9 "Late Payment & Dispute Resolution" policy, then id=10, then invoice-table chunks. No all-or-nothing.
- **hybrid**: equalled pgvector exactly (FTS empty -> contributed nothing). RRF scores all `1/61` (each id in one list at distinct rank).

Honest correction to earlier "hybrid always wins": hybrid only helps when BOTH arms return useful, overlapping lists. When one arm returns nothing, hybrid = the surviving arm. No rescue here.

### Fix applied: plainto_tsquery -> websearch_to_tsquery (PgFtsRepository)
Switched `PgFtsRepository.search` from `plainto_tsquery` to `websearch_to_tsquery` (both WHERE and ts_rank). Why:
- `plainto_tsquery`: AND all words, ignores operators.
- `websearch_to_tsquery`: web-search-style. Bare words still AND, but understands `OR` -> `|`, `"quoted phrase"` -> `<->`, `-word` -> `!`. Never errors on raw input.
- Effect: user can now write `INV-5518 OR dispute resolution late payment` -> `inv & 5518 | disput & resolut & late & payment` -> FTS returns the code chunk AND the policy chunks, so hybrid RRF can actually blend code-hit + concept-hits as intended.
- Default bare-word behavior unchanged (still AND), so existing single-word queries (e.g. integration test `invoice`) behave identically. Negation/phrase/OR are now available as a bonus.
- Tradeoff: requires Postgres 11+ (websearch_to_tsquery added in PG 11). We run pg16, fine.

## FTS language limitation (multilingual)

`schema.sql` builds `tsv` with `to_tsvector('english', content)` and `PgFtsRepository` queries with `websearch_to_tsquery('english', ?)`. The `'english'` arg is a text-search CONFIG (stemming + stopwords), not the alphabet.

- Index-time config and query-time config MUST match, or stems differ and nothing matches. Change one -> change both.
- Non-English text under `'english'`: still tokenizes (exact words match) but wrong stemming (inflections miss) and wrong stopwords (noise). Degrades, not zero.
- Postgres built-in configs (`\dF`): ~29, all European/simple (french, german, spanish, russian, ...). NO built-in for Chinese/Japanese/Korean/Thai/Arabic - those need word-segmentation extensions (zhparser, pg_jieba, pgroonga). Vietnamese has spaces so `'simple'` works okay-ish.
- Escape hatch: `to_tsvector('simple', content)` = lowercase + tokenize, no stemming/stopwords. Language-neutral safe default for mixed/unknown languages.
- Multilingual options: (1) per-language config -> need a `lang` column + dynamic config (can't keep the GENERATED tsv column; compute in app or trigger); (2) `'simple'` everywhere (lose stemming, gain language-independence); (3) lean on VECTORS - embeddings are language-agnostic by nature (multilingual models map dog/chien/perro near each other). This is a genuine vector advantage over FTS: FTS is per-language-configured, vectors are not.
- This sandbox: English corpus -> `'english'` is correct, kept as-is. Flag for the real project port: FTS language config is a real design decision; vectors dodge it.

## Second full code review (2026-06-13) - findings

Reviewed all 18 main source files. Core mechanics sound. New/updated findings, severity-tagged:

### NEW - real gotcha
- **Re-ingest = silent duplicates.** `IngestService.ingest` never deletes existing chunks for a `docId` first. Postgres `id` is auto-generated, so re-ingesting the same `docId` inserts NEW rows (new ids); old rows remain. Qdrant upserts under the new ids too -> chunks accumulate, search returns duplicates, RRF skewed. Fix later: `ingest` should call `delete(docId)` first (true upsert-by-doc semantics). NOT previously documented.

### Correctness (minor)
- **pgvector score can go negative.** `PgVectorRepository.search` returns `1.0 - distance`; cosine distance `<=>` ranges 0..2, so near-opposite vectors yield score down to -1. Ordering stays correct (sorted by distance), but the score is NOT on the same scale as Qdrant's `p.getScore()` (true cosine similarity 0..1). So `/compare` scores are not directly comparable across pg-vs-qdrant. Cosmetic.
- **Qdrant search NPE risk** (already known): `QdrantRepository.search` reads payload keys with no null-check.

### Robustness - wrong HTTP status
- No `@ControllerAdvice`/exception handler -> all user errors return 500 instead of 400: `type=foo`, blank `docId`, `topK=-1` (Postgres `LIMIT -1` error). One small handler fixes all.
- `topK` unbounded (no cap, no negative guard).
- Blank `q=` -> vector path embeds empty string (Ollama may error).

### Design / search quality
- **`/compare` embeds the query 3x** (pgvector + qdrant + hybrid each call `embeddings.embed(query)`), = 3 Ollama round-trips for one query. This is the pgvector ~2481ms cold spike seen in Lesson 3. Skews the timing comparison and is slow. Fix: embed once, reuse the vector for all backends.
- **Hybrid excludes Qdrant** (fuses fts + pgvector only) - by design (design doc s6), but "hybrid" != "all backends".

### Security (sandbox - acceptable)
- SQL all parameterized (JdbcTemplate `?`); pgvector literal built from `float[]` numbers only -> no injection. Good.
- DB creds `rag/rag` in `application.yml`, no auth, Qdrant plaintext gRPC - all fine for a local sandbox, by design.

Verdict: nothing breaks the learning purpose. Priority fix if continuing = re-ingest dedup; nice-to-have = single-embed in `/compare` + a 400 error handler.

## Fixes applied + verified live (2026-06-13)

All three review findings fixed and confirmed against the running app (8 tests still green):

1. **Re-ingest dedup** - `IngestService.ingest` now calls `delete(docId)` first (upsert-by-doc). Verified: Qdrant `points_count` stays at 23 for the 2-doc corpus, no doubling.
2. **Single-embed in `/compare`** - `SearchService.compare` embeds the query ONCE and shares the vector across pgvector/qdrant/hybrid (added a `timed()` helper). Verified: `/compare` timings dropped from the ~2481ms cold-embed spike to pgvector 4ms / hybrid 5ms. Timings now reflect search cost, not 3 Ollama round-trips.
3. **400 not 500** - added `GlobalExceptionHandler` (`@RestControllerAdvice`) mapping `IllegalArgumentException` -> 400 ProblemDetail; `topK` bounded 1..100 via `validateTopK`. Verified: `type=bogus` -> 400 "unknown type: bogus"; `topK=999` -> 400 "topK must be between 1 and 100".

### Lesson 3 completed: OR-query makes hybrid actually blend
After the `websearch_to_tsquery` switch, re-ran the mixed query WITH `OR`:
`INV-5518 OR dispute resolution late payment` -> tsquery `inv & 5518 | disput & resolut & late & payment`.
- **fts**: no longer empty - returns BOTH the dispute/late-payment policy chunks (id=9, id=10) AND the INV-5518 chunks (id=17, id=7, id=6). The OR bridges code-half and concept-half.
- **hybrid**: genuinely blends now. id=9/id=10 appear in BOTH fts and pgvector lists -> RRF `1/61 + 1/61 = 0.0328` -> top. The mixed code+concept query is finally served by one fused ranked list. This is the hybrid payoff the sandbox set out to demonstrate; it only worked once FTS used OR semantics (plainto's AND had made fts empty, collapsing hybrid to vector-only).
Takeaway refined: hybrid's value depends on BOTH arms returning useful lists; the FTS query-builder choice (plainto vs websearch) directly controls whether the keyword arm fires on multi-topic queries.

## Reranker Step 1 (2026-06-19) - DJL deviations from plan

Plan `docs/superpowers/plans/2026-06-17-reranker.md` had three wrong DJL coordinates/APIs. Resolved during Task 1 spike against the real jars (DJL 0.30.0):

1. **BOM artifactId**: plan said `ai.djl:djl-bom`; real artifact is `ai.djl:bom` (djl-bom 404s on Maven Central). Pinned 0.30.0 (latest is 0.36.0; kept the plan's intended version).
2. **PyTorch engine artifactId**: plan said `ai.djl.pytorch:djl-pytorch-engine`; real artifact is `ai.djl.pytorch:pytorch-engine` (managed by the BOM, runtime scope).
3. **CrossEncoderTranslatorFactory does not exist** in any DJL release (checked 0.30/0.33/0.36). Plan's load snippet was fictional. Real API for a cross-encoder reranker in 0.30.0:
   - `StringPair` is `ai.djl.util.StringPair` (NOT `ai.djl.modality.nlp.translator.StringPair`).
   - Build the translator manually: `HuggingFaceTokenizer.newInstance("BAAI/bge-reranker-base")` -> `CrossEncoderTranslator.builder(tokenizer).optSigmoid(true).build()`, then `Criteria...optTranslator(translator)` (NOT `optTranslatorFactory`).
   - Confirmed imports recorded at the top of `DjlSpikeTest.java`; Task 4 (DjlReranker) reuses this exact load path.

Verification done: `./mvnw test-compile` green (imports resolve, production compiles). The actual model run (`RUN_DJL_SPIKE=true ./mvnw -Dtest=DjlSpikeTest test`) is gated and downloads hundreds of MB + native PyTorch libs + the bge-reranker weights; left for manual/owner run, not in CI.

### Reranker design decisions
- **DJL over raw ONNX Runtime**: less boilerplate for the sandbox (DJL bundles tokenizer + engine + model-zoo download; raw ONNX needs manual tokenization). Tradeoff: DJL pulls a large PyTorch native lib on first `djl` run.
- **No Ollama reranking**: Ollama exposes embeddings + generate, but no cross-encoder/rerank endpoint, so a separate engine (DJL) is required for this stage.
- **IdentityReranker is the default** (`app.rerank.provider` unset). The whole feature is wired and tested without any model download; the 13-test unit suite stays green and offline. Only `provider=djl` triggers the download.
- **Over-fetch then trim**: `rerank` runs hybrid for `candidates` (50) results, reranks, then trims to `topK`. The cross-encoder only adds value if it sees more candidates than the final `topK`.
- **CI does not exercise the real model**: `DjlSpikeTest` + `DjlRerankerManualTest` are gated behind `RUN_DJL_SPIKE=true`. Without it they skip, keeping CI fast and network-free.
- **`compare` now has a 5th column** (`rerank`); the existing integration assertion was updated from 4 to 5 keys.

### Real bge-reranker verification - BLOCKED by network (2026-06-19) - DIAGNOSIS SUPERSEDED
> **This conclusion was wrong.** The blocker was never network access: `djl://` resolves against
> DJL's own catalog, and `BAAI/bge-reranker-base` is simply not published there. Kept for the
> record; see "2026-08-05 - DJL reranker fixed" at the end of this file for the real root cause.

Attempted `RUN_DJL_SPIKE=true` run of `DjlSpikeTest` + `DjlRerankerManualTest`:
- `huggingface.co` is unreachable from this environment (SSL connection cannot be established; `os error 10054` connection forcibly closed). Regional block.
- `hf-mirror.com` IS reachable (200) and `HF_ENDPOINT=https://hf-mirror.com` fixes the **tokenizer.json** download (HuggingFaceTokenizer.newInstance then succeeds).
- But model loading still fails: `IllegalArgumentException: Invalid djl URL: djl://ai.djl.huggingface.pytorch/BAAI/bge-reranker-base` at `Criteria.optModelUrls`. The DJL HF-pytorch zoo resolves/converts the model via huggingface.co (HF_ENDPOINT mirror is NOT honored for the zoo model index), which is blocked. `mlrepo.djl.ai` host itself is reachable.
- Conclusion: the real cross-encoder path is correct in code (compiles, imports confirmed, identity/wiring paths all green) but cannot be exercised on a network that blocks huggingface.co. To verify: run on an unrestricted network, OR pre-download the model into the DJL cache (`~/.djl.ai`) and point `optModelUrls` at the local path. Default `IdentityReranker` path is fully verified and unaffected.

## Knowledge base (Tasks 1-9, 2026-07-03)

Full feature: document ingest (chunked by heading), RAG retrieval via hybrid/FTS/pgvector/Qdrant, LLM chat answers, eval (retrieval metrics + judge faithfulness). All 18 tasks implemented, tests green. Key deviations and decisions from the plan:

### Chunker signature changed
- **Plan spec**: `Chunker.chunk(String docId, String text)` - docId passed in to tag each chunk.
- **Actual**: `Chunker.chunk(String text)` - docId not used; breadcrumb source-file path and heading trail extracted from markdown headings in the text itself. Simpler integration; docId is added by the caller (`UploadController`) only at storage time, not chunking time.

### Markdown parsing and table atomicity
- **commonmark-java**: version 0.24.0 (plan's recommended default; no newer-version survey performed).
- **Pipe tables (GFM `| table |` syntax)**: implemented via source-text sniffing (`line.startsWith("|")`) to detect and hold table lines atomic in the chunking logic, avoiding a second markdown-parsing dependency (gfm-tables extension). commonmark core alone parses GFM tables as plain paragraphs; the sniffing layer catches them. Reduces dependency bloat at the cost of a regex; tradeoff accepted for a sandbox.

### Metadata propagation fixes (unplanned, discovered during testing)
- `RrfFusion` and `DjlReranker` were initially updated only in test utilities. During integration testing, fused/reranked chunks silently lost `sourceFile` / `headingPath` metadata (not propagated through the fusion/reranking pipeline). Fixed: both now copy metadata through the merge/rerank steps. This was not itemized in the plan but surfaced as a correctness issue during E2E verification.

### Upload response and Surefire config
- **Response field**: existing DTO field `chunksStored` (not a new `chunks` field guessed by the plan). UI reads `chunksStored`.
- **Eval test exclusion**: Surefire `pom.xml` uses Maven property `${excludedGroups}` (default `eval,eval-judge`) instead of a hardcoded tag list. This allows the eval CLI commands to override via `-DexcludedGroups=` (empty string = run all evals). Without the property indirection, the CLI override would not work.

### Chat model: qwen3:8b with think:false
- **Plan default**: qwen2.5:7b.
- **Actual**: qwen3:8b (newer generation, swapped same-run, judge eval results: qwen2.5 = 14/18 yes, qwen3 = 18/18 yes).
- **Configuration**: qwen3 is a reasoning/thinking model; without `think: false` in the Ollama request body, reasoning blocks pollute the final answer. Added `think: false` to suppress them. Chat model configurable via `app.chat.model` property.

### Eval results (2026-07-03, repository docs as corpus, 18 golden questions)
Retrieval metrics and faithfulness smoke test:
- **Hybrid**: recall@5=1.000 MRR=0.935 hit@1=0.889 (top-K recall = fraction of questions where correct doc in top-5; MRR = mean reciprocal rank; hit@1 = correct answer at rank 1).
- **FTS**: recall@5=0.222 (expected keyword weakness; paraphrase questions fail, exact-match questions succeed).
- **pgvector / qdrant**: symmetric (same vectors, same HNSW index -> same results), both better than FTS for semantic queries, weaker than hybrid for mixed code+concept queries.
- **Judge eval (faithfulness)**: qwen3:8b evaluated 18 LLM answers (one per golden question) as yes/no. Result: 18/18 yes (all answers grounded in retrieved chunks, no hallucination on this corpus). Smoke test only (small sample); larger scale evals recommended.

### Tests verified green
- Full `./mvnw -q test` passes: 18 units + 1 integration test covering knowledge-base end-to-end (document import, chunk retrieval, chat answer, eval metrics).
- Eval tests optional/gated: `./mvnw test "-Dgroups=eval" "-DexcludedGroups="` runs retrieval evals; `./mvnw test "-Dgroups=eval-judge" "-DexcludedGroups="` runs faithfulness evals (both need Docker + Ollama).

## 2026-07-06 - REST wiki-import endpoint with live progress

Added `POST /projects/{projectId}/import-wiki` so a whole Azure-wiki clone imports with one
call, STREAMING live progress instead of blocking silently. Mirrors `ChatController`: NDJSON
frames over `StreamingResponseBody` (`application/x-ndjson`), one flush each:
- `{"type":"start","total":N}` once (N = page count, known before the loop).
- `{"type":"progress","done":k,"total":N,"doc":...}` after each page ingested.
- `{"type":"done","pagesImported":N}` on normal completion.
- `{"type":"error","message":...}` if the import throws mid-stream (response already 200).

`WikiImporter` got a `ProgressListener` functional interface + an `importDir(projectId, root,
listener)` overload; the old 2-arg `importDir` delegates with a no-op listener, so
`WikiImporterManualTest` still compiles (backward compatible). `total` is `pages.size()`,
reported from the first callback.

Validation runs BEFORE the stream (fail-fast -> clean 400 via `GlobalExceptionHandler`, not a
mid-stream error frame): project must exist, `path` non-blank, and `Files.isDirectory(path)`.

Import uses the configured `app.graph.edges` (default `structural` = no LLM). No `edges`
override param on the endpoint. The existing `spring.mvc.async.request-timeout: 600000` (10 min)
covers long imports.

**SECURITY:** this endpoint reads an arbitrary server-side directory path supplied by the
caller. Acceptable ONLY for a localhost single-user dev sandbox with no auth - it is a
dev/operator tool, not a public API. If this app is ever exposed remotely, gate it (config flag
or jail imports under a fixed base dir) - otherwise it is a directory-traversal / arbitrary-read
lever.

Tests (`WikiImportControllerIntegrationTest`, `app.graph.edges=structural`, no Ollama): MockMvc
async dispatch asserts 3 `progress` frames + `start.total=3` + final `done.pagesImported=3` and
3 docs landed via `pgVector.listDocuments`; a direct `WikiImporter` callback test asserts
`total==3` and `done` increments 1..3 (regression guard independent of streaming plumbing); a
negative test asserts a non-existent path -> HTTP 400. Full suite green (100 tests, 3 skipped).

## 2026-07-06 - Real wiki import: hardening from live run (449-page Azure/Confluence clone)

First real bulk import surfaced three issues; all fixed:

1. **Empty pages** - wiki stubs (title-only or blank) were ingested as empty chunks. Fix:
   `WikiImporter` skips blank/whitespace-only files (`isBlankFile`) before counting/ingesting.
   The `.ps1` converter likewise drops empty markitdown output. 449 files -> 429 non-empty.

2. **Oversized chunk vs embedding context** - MarkdownChunker keeps tables/code atomic; a big
   Confluence table exceeds nomic-embed-text's context ("input length exceeds the context
   length" 500 from Ollama). Fix: `IngestService.capToBudget` hard-caps every chunk at
   `MAX_CHUNK_CHARS`, splitting at whitespace (hard-cut for a single giant token) and
   renumbering. First try 4000 chars still failed on dense requirement tables (IDs/numbers/pipes
   tokenize near 1 char/token -> >2048 tokens); lowered to **2000 chars** = safe under the
   2048-token limit worst-case. Prose chunks (maxWords=300 ~1800 chars) are unaffected; only
   atomic tables split.

3. **One bad page aborted the whole import** - the stream died mid-run. Fix: `WikiImporter`
   wraps each page in try/catch, rolls back partial chunks (`ingest.delete`), and reports via a
   new `ProgressListener.onError` default method. The endpoint emits a `{"type":"skip",...}`
   frame per failure and a final `{"type":"done","pagesImported":N,"pagesFailed":M,"total":T}`.
   Result: 429/429 imported, 0 failed after the 2000-char cap.

**Converter script bug (`scripts/convert-to-md.ps1`)**: `-Include` is silently ignored when
combined with `-LiteralPath`, so the first run walked every file (converted `.order`/PNG icons
into junk `.md`). Fixed to filter on real `.Extension` and exclude `.attachments`/`.git`/`.images`.
Note: the wiki's pdf/docx all live under `.attachments/` (excluded), so nothing was converted -
those attachment docs would need an explicit opt-in to import.

**Known edge (not fixed):** `docIdOf` uses only the filename, so two pages with the same
filename in different folders collide (one overwrites the other). Live import: 429 files ->
428 docs = 1 collision. Acceptable for now; a path-qualified docId would fix it.

Live result: project "docmaster" = 428 docs, 7,536 chunks.

## 2026-07-06 - "Show AI thinking" toggle + reasoning-leak fix

qwen3:4b was leaking its chain-of-thought into ask answers (raw reasoning + a stray `</think>`
shown before the answer). Root cause: `think:false`/`/no_think` does NOT stop qwen3 reasoning -
it just makes it dump into `message.content` instead of the clean `message.thinking` field. The
old streaming `ThinkFilter` only stripped a well-formed `<think>...</think>`, so a tag-less dump
leaked.

Fix + feature (Copilot-style collapsible reasoning):
- **Always send Ollama `think:true`** on the streaming path. The model reasons either way (same
  cost), and `think:true` routes reasoning to the separate `thinking` field so `content` is
  always a clean answer - the leak is gone regardless of the UI toggle.
- New `ChatProvider.chatStream(system, messages, boolean think, onToken, onReasoning)` overload;
  the old 3-arg delegates (think=false, no-op reasoning). `ChatService.chatStream` and the
  `/chat/stream` endpoint gained the `think` flag (in `ChatRequest`) and a reasoning channel that
  emits `{"type":"reasoning","text":...}` frames.
- The `think` flag only controls **forwarding**: on -> reasoning frames stream to the client;
  off -> reasoning dropped server-side (`reasoningSink` no-op), answer still clean.
- `ThinkFilter` now has two sinks (answer/reasoning) and also defensively captures a dangling
  `</think>` with no opening tag (older leak shape), routing it to reasoning instead of the answer.
- Ollama returns reasoning in `message.thinking` (content empty meanwhile); `Message` record
  gained that field and the stream loop forwards it.
- Frontend: a "Show AI thinking" checkbox on the ask screen sends `think`; reasoning streams live
  into a collapsible "💭 Thoughts" box (expanded while thinking, auto-collapses when the answer
  starts, re-openable via the toggle). Persists after streaming via `renderThread`.

Note: the non-streaming `chat()` path (used by follow-up query condensing) still sends
`think:false` + `stripThink`; a tag-less leak there is possible but only affects an internal
query rewrite, not user-facing text. Left as-is.

Verified live: think=false -> 0 reasoning frames, clean answer, no leak; think=true -> reasoning
streamed to its own channel, answer still clean. Full suite green (105 tests).

## 2026-07-29 - Wiki retrieval eval harness (`WikiRetrievalEvalTest`)

Wired `golden-wiki.yaml` (11 questions, previously unrunnable) into a real test. `GoldenSet.load()`
gained a resource-path overload (`load(String)`; the existing no-arg `load()` is untouched and
still used by `RetrievalEvalTest`). `WikiRetrievalEvalTest` is tagged `eval-wiki`, added to the
pom's existing `${excludedGroups}` default (same mechanism as `eval`/`eval-judge`, no new plumbing
needed), and run with `./mvnw test "-Dgroups=eval-wiki" "-DexcludedGroups="`.

### Live stack instead of Testcontainers - why re-import was rejected
`RetrievalEvalTest` builds its own throwaway Testcontainers corpus per run. That does not work for
the wiki: the corpus is private (429 source files, cannot ship in the repo) and re-embedding 7,536
chunks costs real wall-clock time - not viable on every test run. `WikiRetrievalEvalTest` instead
declares no Testcontainers at all: Spring boots against `application.yml` and queries whatever is
ALREADY imported in the live local stack (Postgres + Qdrant + Ollama already running on the dev
box), resolving the project by NAME (`docmaster`, override with `-Deval.wiki.project=<name>`)
rather than a hardcoded id.

### Skip-not-fail precondition rule
A fresh clone of this repo can never have the private wiki corpus, so a missing project must never
be a hard failure - that would be permanent and meaningless for anyone who is not this dev box.
`requireCorpus()` uses `Assumptions.assumeTrue(...)` (project exists, has chunks > 0) and
`Assumptions.abort(...)` (Postgres unreachable) so the test SKIPS rather than fails when a
precondition is absent. Only once the corpus and stack are both confirmed present does the test
proceed to assert anything.

### Read-only by construction
`WikiRetrievalEvalTest` injects only `SearchService`, `ProjectRepository`, and `Reranker` (the last
one solely to print which reranker implementation is active) - never `IngestService`. It also
carries `@TestPropertySource(properties = "spring.sql.init.mode=never")` so Spring does not re-run
`schema.sql` against the live database. No code path in this class can write or delete a row.

### Surefire and `-D` propagation - the planned pom fallback was never needed
The plan anticipated a `pom.xml` `<systemPropertyVariables>` fallback in case Surefire's forked JVM
did not see `-Deval.wiki.project` / `-Deval.rerank` / `-Dgroups` / `-DexcludedGroups`. Not needed:
**`-D` system properties DO propagate into the forked Surefire JVM as-is.** Confirmed two ways:
`-Dgroups=eval-wiki "-DexcludedGroups="` correctly selected and ran only `WikiRetrievalEvalTest`,
and `-Deval.rerank=djl` reached the `@DynamicPropertySource` override and switched in the real
`DjlReranker` bean (see the defect note below) - both without touching `pom.xml`.

### Measured numbers (`docmaster` project, 428 docs / 7,536 chunks, 11 golden questions, topK=10)
- **fts**: recall@5=0.182 MRR=0.182 hit@1=0.182 - 2 of 11 questions hit (both at rank 1), 9 misses.
- **pgvector / qdrant / hybrid / rerank / graph**: identical - recall@5=0.909 MRR=0.919
  hit@1=0.909. 10 of 11 questions land at rank 1; the 11th (open shortcomings of the Job API and
  Data API) ranks 9th on every one of these five backends, never missed outright.
- **graph vs hybrid**: expected-doc rank differs on 0 of 11 questions; the full ordered top-10
  `(docId, chunkIndex)` list is identical on 11 of 11. Interpretation and the corpus-dependence of
  the hybrid-vs-vector result: `docs/LEARNINGS.md` §11 and §14.

### Known defect (pre-existing, not caused by this work): `DjlReranker.loadModel()` cannot load
`-Deval.rerank=djl` correctly switches the eval to the real cross-encoder bean - the eval prints
`reranker=DjlReranker` in its header, proving the flag, the `@DynamicPropertySource` override, and
`RerankConfig`'s bean selection all work end to end. The very next step fails:
`DjlReranker.loadModel()` throws `IllegalStateException: Failed to load reranker model:
BAAI/bge-reranker-base`, caused by `IllegalArgumentException: Invalid djl URL:
djl://ai.djl.huggingface.pytorch/BAAI/bge-reranker-base`, thrown while DJL is still parsing and
registering the model URL (`Criteria.Builder.optModelUrls` -> `DefaultModelZoo.parseLocation` ->
`RepositoryFactoryImpl$DjlRepositoryFactory.newInstance`) - synchronously, in about 9 seconds,
**before any HTTP request for model weights is attempted.** No download starts; this is not the
"slow, many-minutes" scenario originally anticipated for a small-GPU box.

Reproduced independently with this repo's own pre-existing, unmodified `DjlSpikeTest`
(`RUN_DJL_SPIKE=true ./mvnw -Dtest=DjlSpikeTest test` -> identical exception in 1.6s), so this
predates the eval harness and is not caused by it. Network reachability (`huggingface.co:443`,
`mlrepo.djl.ai:443`) and DJL dependency versions were checked and ruled out as causes.

**Consequence:** `app.rerank.provider=djl` is currently unusable on this machine, so the `rerank`
backend has only ever run as the no-op `IdentityReranker`. The `LEARNINGS.md` §14 claim that a real
cross-encoder is what gives graph expansion its teeth has therefore never actually been testable
here - it is UNTESTED, not refuted. Fixing `DjlReranker.loadModel()`'s model-URL construction is
separate work; `DjlReranker.java` was not touched by this task (out of scope, do-not-modify).

> **RESOLVED 2026-08-05** - see the next section. The cause was not the URL construction and not
> the network; the configured model id was absent from DJL's catalog.

## 2026-08-05 - DJL reranker fixed (root cause: model id, not network, not URL syntax)

### Root cause
`djl://ai.djl.huggingface.pytorch/<id>` does **not** address the HuggingFace Hub. It addresses
DJL's own model zoo at `https://mlrepo.djl.ai`, which carries only the models DJL has pre-traced
to TorchScript - the PyTorch engine cannot consume raw `.safetensors` / `.bin` weights. Traced to
the exact line by reading the DJL 0.30.0 sources: `RepositoryFactoryImpl:262` throws
`IllegalArgumentException("Invalid djl URL: " + uri)` in the branch where `zoo.getModelLoader(artifactId)`
returned `null`. Two branches above it would have said "ModelZoo not found in classpath", so the zoo
resolved fine - the artifact inside it did not exist.

`HfModelZoo` indexes exactly five NLP applications from `mlrepo.djl.ai`. Its
`nlp/text_classification` index holds 24 entries, and the two cross-encoders in it are
`BAAI/bge-reranker-v2-m3` and `cross-encoder/mmarco-mMiniLMv2-L12-H384-v1`.
**`BAAI/bge-reranker-base` is not among them and never was.** The message "Invalid djl URL" reads
like a syntax error, which is what sent the 2026-06-19 diagnosis toward a regional network block.

Why that older diagnosis looked plausible and was still wrong: the *tokenizer* really does come
from the HuggingFace Hub (`HuggingFaceTokenizer.newInstance(id)`), and it really did need the
`hf-mirror.com` workaround. So one model id was being fed to two different registries with
different catalogs, and only the zoo half was failing. In the 2026-08-05 environment
`huggingface.co` is reachable again, the tokenizer loads with no mirror, and the zoo lookup still
failed - which is what isolated the real cause.

### Fix
- `app.rerank.model` default is now `cross-encoder/mmarco-mMiniLMv2-L12-H384-v1` (in both
  `RerankProperties` and `application.yml`), with the zoo constraint stated in a comment at each.
- `DjlReranker.loadModel()`'s failure message now names the constraint instead of letting DJL's
  "Invalid djl URL" stand alone.
- `DjlSpikeTest` no longer hardcodes a model id; it reads `new RerankProperties().getModel()`.
  The hardcoded literal was the mechanism of the whole bug - the spike and the configuration were
  two independently written copies of the same id, so neither could catch the other drifting.

Model choice: `mmarco-mMiniLMv2-L12-H384-v1` is multilingual (the wiki is German and English),
MS MARCO ranking-trained, and about 470 MB. `BAAI/bge-reranker-v2-m3` is the stronger zoo option
but is XLM-R-large at roughly 2.2 GB, and DJL selects the **CPU** engine on this box
(`No matching cuda flavor for win-x86_64 found: cu065`), so every rerank is 50 CPU forward passes
per query. Swapping is a one-line property change if the quality tradeoff is worth the latency.

### Measured effect - the §14 hypothesis is now TESTED, and it did NOT hold
Same 11 golden questions, same corpus (`docmaster`, 428 docs / 7,536 chunks), topK=10. The
identity-reranker baseline was re-run immediately after and reproduced its earlier numbers
exactly, so this is a clean before/after.

| backend  | recall@5 | MRR (identity) | MRR (cross-encoder) |
|----------|----------|----------------|---------------------|
| hybrid   | 0.909    | 0.919          | 0.919 (not reranked) |
| rerank   | 0.909    | 0.919          | **0.909**           |
| graph    | 0.909    | 0.919          | **0.909**           |

- `graph vs hybrid` went from `rank differs 0 of 11; top-10 identical 11 of 11` to
  `rank differs 1 of 11; top-10 identical 0 of 11`.
- So the cross-encoder DID give graph expansion teeth in the mechanical sense - graph is no longer
  a byte-identical no-op, the top-10 order now differs on every single question.
- But the bite is negative. The one hard question ("Which known shortcomings of the Job API and
  Data API are still open for the invoice services?") sat at rank 9 under hybrid and identity
  rerank; the cross-encoder pushed it **out of the top 10 entirely** (rank 0) on both `rerank` and
  `graph`. That single demotion is the whole MRR drop.
- `rerank` and `graph` produce identical metrics to each other. Graph expansion still contributes
  nothing the reranker does not already do.

Honest scope of this result: it is one corpus, one golden set of 11, and one reranker model that
is **not** the model originally intended. A stronger cross-encoder (`bge-reranker-v2-m3`) is
untried here, and MS MARCO passage ranking is arguably out of domain for a German-language
internal tech wiki. The claim that is now dead is the unconditional one - "a real cross-encoder is
what gives GraphRAG its teeth" is not something this project has evidence for, and the first real
measurement points the other way.

### Still open (not fixed here)
`app.rerank.maxLength` (512) is dead configuration - `DjlReranker` never passes it to
`HuggingFaceTokenizer`, which is why every run logs
`maxLength is not explicitly specified, use modelMaxLength: 512`. Harmless today because the
model's own default is also 512. Left alone deliberately: unrelated to this defect, and one fix at
a time.

## 2026-08-05 - Retrieval eval regression gate (drill C)

`WikiRetrievalEvalTest` now asserts against `src/test/resources/eval/baseline-wiki.yaml` instead of
only printing. Spec: `docs/superpowers/specs/2026-08-05-eval-regression-gate-design.md`.

### Why the wiki eval and not the self-corpus eval
`RetrievalEvalTest` ingests `docs/` (`RetrievalEvalTest:89`), so its corpus is this repo's own
documentation and its numbers move whenever anyone edits a doc - four files under `docs/` changed on
2026-08-05 alone. Gating it produces failures caused by writing documentation, which get ignored
within a week. The wiki corpus is frozen and reproduced its numbers exactly on a same-day re-run, so
movement there is attributable to code. The cost of that choice is that the gate can never run in CI
or on a fresh clone; it is pre-merge discipline for one machine. Tracked in `ROADMAP.md`.

### Two checks, because aggregates alone would have missed the real regression
Floors on recall@5/MRR/hit@1 with a 0.02 tolerance, PLUS a rule that no question may go from found
to missed. The second check exists because of a measured case: when the cross-encoder pushed one
question out of the top 10 on 2026-08-05, recall@5 and hit@1 did not move at all (that question had
been at rank 9, outside both windows) and MRR moved only 0.010. Any tolerance comfortable enough to
live with would have hidden it. That case is encoded as a unit test
(`BaselineComparisonTest.failsOnANewMissEvenWhenEveryAggregateMetricIsWithinTolerance`).

### Why 0.02
With 11 questions the metrics are quantized: recall@5 and hit@1 move in steps of 1/11 = 0.091, so
any tolerance below that makes them exact-or-better and only MRR is actually tuned. On the MRR
scale, a question slipping rank 1 to 2 costs 0.045 (fails), rank 9 to 10 costs 0.001 (passes).
Measured noise is currently zero, so 0.02 is headroom against nondeterminism that has not appeared,
not against observed variance.

### Variant derived from the bean, not the flag
The baseline has one section per reranker variant, keyed by the active `Reranker` bean's simple
class name rather than by `-Deval.rerank`. Deriving it from the flag would let
`app.rerank.provider=djl` set in `application.yml` write djl numbers into the identity section.

### Corpus fingerprint
The baseline records project id, name, doc count, and chunk count. A re-import shifts chunk ids and
moves every number for reasons that are not regressions; without the fingerprint that surfaces as
six simultaneous backend failures that read exactly like a real defect. The gate compares the
fingerprint first and, on mismatch, reports only that with the regeneration command. It is a
staleness check, not an integrity check: a corpus edited in place that preserves both counts is not
detected.

### Writes go to the source tree
`EvalBaselineStore.write` targets `src/test/resources/eval/baseline-wiki.yaml`, not the classpath
copy under `target/`. Writing to the classpath copy would be discarded by the next build and the
update would silently do nothing. Metrics are rounded to 3 decimals on write to keep the committed
file readable; the rounding error is at most 0.0005 against a 0.02 tolerance and only ever lowers
the floor.

### What is deliberately not gated
`FaithfulnessEvalTest` (LLM-judge output needs its own noise study first), `RetrievalEvalTest`, and
anything in CI. The full golden question list is recorded in the baseline so that adding a question
is a printed notice rather than a build failure.

### Build note from execution
A stale incremental compile broke JUnit discovery mid-task with
`NoClassDefFoundError: GoldenEntry` (unqualified, so the compiled `WikiRetrievalEvalTest.class`
referenced the type in the default package). `./mvnw clean test` fixed it. Worth knowing: adding
classes to the `eval` package while running targeted `-Dtest=` builds can leave `target/test-classes`
inconsistent, and the symptom looks like a code defect rather than a build-state one.

---

## 2026-08-05 - Per-chunk relevance feedback (RAG-MASTERY move 2, ROADMAP "Option A")

Executed inline (no subagents), five units: schema + repository, controller, UI thumbs, offline
eval reader, docs. What follows is the part that is not visible in the diff.

### Decisions that were not in the spec

**Upsert instead of the specced "simple insert".** ROADMAP described an append-only log. Built as
`UNIQUE (project_id, doc_id, chunk_index, query_text)` + `ON CONFLICT DO UPDATE`, with
`DELETE /feedback` for un-voting. Reason: the only consumer is an eval that wants clean
`(query, chunk, relevant)` triples. An append log pushes latest-wins dedupe into every consumer and
buys history nobody asked for. Cost of the choice: a user changing their mind is not recoverable.

**Labels key on `(doc_id, chunk_index)`, never `chunks.id`.** Ingest is delete-then-insert per
document, so chunk ids do not survive a re-import. `updated_at` is kept so a stale label set can at
least be dated.

**`query_text`, capped at 500 characters, enforced in three places.** The column is part of a
btree UNIQUE index, and Postgres rejects index rows over ~2704 bytes. The DB has a CHECK, the
controller returns 400 over the cap, and the frontend hides the thumbs rather than offering a
control that would fail. A CHECK alone would have surfaced as a 500 on a long query.

**Backend and rank are deliberately NOT stored.** Tempting, since the UI knows which backend
produced the hit. But a label answers "is this chunk relevant to this question", which is
backend-independent - the eval replays the query through all six backends itself. Storing the
backend would invite per-backend label sets that cannot be compared.

**Validation lives in the controller, no service layer.** A `FeedbackService` would have been a
pass-through; `DocumentController` already talks to a repository directly. Project existence is
checked via `ProjectService.exists` so an unknown project is a 400, not an FK violation surfacing
as a 500.

**Chat labels use the RAW question, not the condensed one.** `ChatService` may rewrite a follow-up
before retrieving. The label is stored against what the human typed, because that is what they were
judging. Consequence: a label from chat and a label from the search box for the same wording share
a key, which is intended; but the eval replays the raw text, so a label collected after a condense
may be scored against a slightly different retrieval than the user saw. Accepted - the alternative
(storing the condensed query) makes the label unreadable to a human reviewer.

### Frontend

- One `chunkLabels` map keyed `query \0 docId \0 chunkIndex`, shared by search rows and citation
  chips, so the same chunk labelled in Ask shows as labelled in Search for the same query.
- Optimistic update with rollback: the thumb flips immediately and reverts with an error toast if
  the request fails, so the UI never shows a label the server does not have.
- `GET /feedback?projectId&query` is called after every search (and after an answer finishes) to
  restore thumbs across reloads. A failed load is swallowed - labels must never break search.
- Thumbs on search rows are hidden until row hover (or when a label exists), keeping the low-clutter
  requirement from the ROADMAP note. `e.stopPropagation()` on the buttons, because the whole row is
  a click target for open-in-context.

### Eval reader (`FeedbackPrecisionEvalTest`, tag `eval-feedback`)

Same posture as `WikiRetrievalEvalTest`: no Testcontainers, reads the live stack, read-only by
construction, skips rather than fails when the data is not there (fewer than 10 labels).

- **precision@k over judged hits only.** Unlabelled hits are ignored, not counted as irrelevant.
  With sparse labels the alternative measures click volume. The trade-off is that precision on two
  labels looks like precision on twenty, which is why **coverage (judged / returned)** is printed in
  the same table and a notice fires when coverage is thin.
- **MRR(up)** - mean reciprocal rank of the first thumbs-up chunk - is the number that actually
  answers the reranker question, since precision ignores position.
- **Report, not a gate**, unlike drill C. The golden set is frozen so it can be gated; labels grow
  with every click, so a committed threshold would fail tomorrow for an honest reason.
- One assertion survives: if no labelled chunk appears in the top 10 of ANY backend, the run fails
  with a message pointing at corpus re-ingest, because a table of zeros otherwise reads as "all
  backends are bad".

### Known limitation found during self-review: group search cannot be labelled

With "search whole group" on, results can come from a sibling project, but `SearchHit` carries no
`projectId` - so the label would be filed under the ACTIVE project and the eval, which replays each
query scoped to one project, would never see that chunk again. The thumbs are therefore hidden
while the group toggle is on, rather than silently writing labels that cannot be replayed. The
proper fix is a `projectId` on `SearchHit`, which touches all four repositories and was out of
scope here.

Related and accepted: a label collected while document scope chips were active is replayed
*unscoped within its project*. That is intentional - the label rates the chunk against the
question, and the eval measures the backend rather than reproducing one user's filter.

### Verification performed

- Full default suite: **145 tests, 0 failures, 3 skipped** (the 3 pre-existing manual DJL tests).
- Live smoke against the running app on :8085 - upsert flips the rating in place (one row, id
  unchanged), bad rating / unknown project / out-of-range limit all return 400, DELETE clears, and
  a second GET returns `[]`.
- `FeedbackPrecisionEvalTest` was exercised end to end by writing 6 synthetic labels through the
  API against project 5, running the eval, then deleting them again. It produced the full table, so
  the reader works - but those numbers were self-fulfilling by construction (rank 1 labelled up,
  rank 4 labelled down) and were NOT recorded anywhere as a finding. With the labels gone the test
  skips, which is the correct behaviour on a clean install.
- The UI was verified by syntax check and by confirming the served `app.js` / `style.css` carry the
  new code. The thumbs were not clicked in a real browser - that is the natural first step next
  session, and it is also what produces the first honest numbers.

### Not built

No `Option B` score blending, no aggregate stats endpoint, no UI for browsing collected labels
(`GET /feedback` returns JSON and that is enough for now). The eval has no numbers yet - it needs
real clicks against the `docmaster` corpus, which is a human step, not a code step.

---

## 2026-08-05 - Permission-aware retrieval (RAG-MASTERY move 3, section 1)

Executed inline. Three user decisions taken up front: add `spring-boot-starter-security` (rather
than a fake header principal), do section 1 before section 5, and backfill the existing corpus to a
`public` group rather than letting it go dark or fail open.

### Design decisions

**`SearchContext` is a required first argument, with no bypass overload.** Every retrieval entry
point takes `(principal, groups)`. The old identity-free overloads were deleted rather than kept
for convenience, so "search without an identity" is not expressible. Tests use
`TestContexts.PUBLIC`; there is deliberately no superuser context, because a back door added for
tests outlives the test.

**The label predicate is built with placeholders, not a literal.** `allowed_groups && ARRAY[?,?]`
rather than a hand-built `{"a","b"}` string, so group names can never be concatenated into SQL.
The one place a literal is still built is `insert`, where `toArrayLiteral` escapes quotes and
backslashes.

**NULL means "pre-ACL", `{}` means "nobody".** The column is nullable so the one-time backfill can
find rows written before access control existed. After that, ingest always writes at least one
group, so re-running the backfill UPDATE cannot re-open a deliberately restricted chunk. Both NULL
and `{}` are false under `&&`, so the default stays deny either way.

**Qdrant gets its own migration.** `QdrantAclBackfill` (ApplicationRunner, `is_empty` filter,
idempotent, failure logged not fatal). Without it the 7,536 pre-ACL points would be invisible to
the qdrant backend while pgvector kept working - the eval gate would catch the regression, but it
would read as a search defect rather than a missing migration.

**Group ownership on writes.** `CurrentUser.requireOwnGroups` rejects labelling a document with a
group you are not in (403). Found during self-review: read filtering alone would have let `bob`
stamp a document `hr`.

**Unknown group names are rejected at ingest** against the configured directory. A typo would
otherwise produce a document that silently nobody can read, which is much harder to notice than an
upload error.

**Streaming identity is captured on the request thread.** `ChatController` resolves the context
before returning the `StreamingResponseBody`, because the body runs on an async thread where
`SecurityContextHolder` is no longer populated - and where a pooled thread might hold someone
else's context.

**Feedback endpoints gained a visibility check** (`PgVectorRepository.isVisible`) and the label
dump joins `chunks`, because a label carries a document id plus a human's query text.

### What broke in the test suite, and why it is worth knowing

- `@WebMvcTest` slices do NOT pick up the application's `SecurityConfig`; they get Boot's default
  chain, which has CSRF enabled. Every POST/PATCH/DELETE became a 403. Fixed with
  `@Import(SecurityConfig.class)` rather than `.with(csrf())`, so the slice tests exercise the real
  policy instead of a different one.
- MockMvc integration tests needed both an identity AND a group:
  `@WithMockUser(username = "alice", authorities = {"GROUP_public"})`. A plain `@WithMockUser` gets
  `ROLE_USER`, no groups, and therefore reads nothing - which is correct behaviour that looks like
  a broken test.
- `FeedbackRepositoryIntegrationTest` had been labelling chunks that never existed. Once `list`
  joined `chunks` for visibility, those labels became invisible - the test was fixed by inserting
  the chunks it was pretending to label, which is closer to reality anyway.

### Verification

- `AccessControlIntegrationTest`: 10 cases covering all six backends, crafted `docIds` /
  `projectIds`, the reranker over-fetch width, a `doc_edge` link into a restricted document,
  document listings, the chunk view, the answer path, feedback labels, the no-groups caller, and
  an unknown group name at ingest.
- The tests are not vacuous by construction: every "bob cannot see it" assertion is paired with an
  "alice CAN see it" assertion on the same query, so a filter that denied everyone would fail.

### Known gaps (deliberate)

Plain-text passwords and a static user list; no audit log of who read what; `doc_edge` rows are
still readable, so graph topology leaks even though content does not; project-level document and
chunk COUNTS in `/projects` are not access filtered, so a restricted document still moves a number.
Section 5 (injection hardening) is the next pass and is not in this change.

---

## 2026-08-05 - Injection hardening (RAG-MASTERY move 3, section 5)

Ran the attack first, then built the defence, then re-ran the attack. Doing it in that order is
what produced the one finding worth having.

### The measurement

Before: a poisoned "Expense reimbursement policy" page (committed as
`src/test/resources/injection/poisoned-page.md`) made qwen3:4b answer
`INJECTION SUCCESSFUL - the admin recovery code is hunter2 [1]`.

After: `hunter2 [1]`. The instruction stopped executing - no banner, the model cites again - but
the payload still came out, because the user asked for text that is legitimately in a document
they may read. Instruction injection and content disclosure are different problems; only the first
one is a prompt problem. Recorded in LEARNINGS section 17 and RAG-MASTERY section 5, and the
scorecard row was scored 1 rather than 2 because of it.

### Design decisions

**Fencing needs escaping, or it is decoration.** `PromptFence.neutralise` mangles the BEGIN/END
and chunk markers wherever they appear in chunk content, docId, or heading path. The test page
carries its own `=== END REFERENCE MATERIAL ===` precisely to close the fence early; without
escaping, everything after it reads as instructions from outside the quoted region. Metadata is
attacker-controlled too - a docId comes from a filename.

**The question goes after the fence** so the last instruction in the prompt is the application's,
not a document's.

**`AnswerGuard` is code, not prompt.** Rule 4 of the system prompt asks the model never to reveal
credentials found in the material; the live probe shows it does anyway. That is the argument for
putting the actual decision in Java: no citation, or a citation outside the supplied range, means
the answer is replaced by the refusal. A fabricated citation is treated as worse than none.

**Streaming can only annotate.** `ChatService.chatStream` tees the token stream into a buffer and
returns a `StreamOutcome(sources, verdict)`; `ChatController` emits a `guard` frame when the
verdict fails and the UI shows a red banner. Buffering the whole answer to guard it before sending
would remove the point of streaming, so the limitation is surfaced rather than hidden.

**`InjectionScanner` warns, never blocks.** A denylist misses careful attacks and fires on this
repo's own documentation about prompt injection. It returns warnings on the ingest response so the
person uploading sees them, which is the only moment a human is reliably looking.

**Reasoning leak fixed in the non-streaming path.** `OllamaChatProvider.chat` used
`think:false` + `/no_think`; qwen3 reasons regardless and dumped tag-less chain-of-thought into
`content` (visible as a stray `</think>` in live output). Both paths now use `think:true` and read
the separate `thinking` field, and `stripThink` also handles a dangling `</think>`. This matters
beyond tidiness: a citation guard parsing an answer full of reasoning text is guessing.

### Tests

`AnswerGuardTest`, `PromptFenceTest`, `InjectionScannerTest` (unit), plus `InjectionDefenceTest`,
which drives the real `AskService`/`ChatService` with a stand-in model that ALWAYS obeys the
injection. That stand-in is the point: a defence that only works because the model behaved is not
a defence. Suite: 179 tests before the guard-frame case was added, 0 failures.

### Deliberate gaps

An injection that keeps citing while misrepresenting the source passes the guard. Streaming warns
after the fact. The scanner is a smoke alarm. And nothing here addresses a poisoned page being in
the corpus at all - that is section 1 access labels plus ingest hygiene.

---

## 2026-08-05 - Per-request RAG trace (RAG-MASTERY move: section 6)

### Design decisions

**JSONB for the shape-changing parts, columns for what you filter on.** `retrieved` and
`stage_latency_ms` are JSONB; principal, ts, backend, tokens and guard_reason are columns. Adding a
stage or a per-hit field then needs no migration, and neither JSONB field is ever joined on.

**`searchTraced` is a separate method, not a parameter on `search`.** Observation must not be able
to change what retrieval returns, and the six existing `search` call sites stayed untouched. The
traced variant recomputes nothing - it wraps the same switch with timers.

**Tracing never throws.** `TraceRecorder` catches every RuntimeException and logs. A trace exists
to explain a request; it must not be able to fail one.

**The trace stores the model's ORIGINAL answer, not the guarded replacement.** Debugging an answer
that `AnswerGuard` blocked is impossible if the blocked text was thrown away. `guard_reason` records
why it was blocked.

**`condensed_query` is stored only when it differs from the raw question.** A condensed query equal
to the raw one is noise, and the difference is precisely what breaks follow-up retrieval.

**Token counts may be null.** Ollama reports `prompt_eval_count` / `eval_count`; a provider that
does not is recorded as null rather than 0, because "not measured" and "free" are different facts.
`ChatProvider` gained `chatDetailed` and a 6-arg `chatStream` with an `onUsage` sink, both with
defaults that chain DOWN to the simplest overload, so an existing provider keeps working.

**Retention from day one.** `app.trace.keep` (500 rows per principal) pruned after each insert. No
scheduler: one extra DELETE per answer is nothing next to a 200 second generation.

**Traces are access-controlled.** `GET /traces` returns only the caller's own rows - a trace holds
the question someone typed and the documents it matched, the same leak class as document titles in
section 1.

### Verification

- `TraceRepositoryIntegrationTest` (5 cases: JSONB round-trip, cross-principal isolation, duplicate
  request id, prune, null token counts) and `TraceControllerTest` (4 cases, including that a
  `principal` request parameter is ignored).
- Full suite: **188 tests, 0 failures, 3 skipped.**
- Live: one real chat request through the running app produced a `trace` frame, a stored row, and
  cross-user isolation (alice sees 0 of haiks' traces).

### The first trace was immediately useful

`embed 6,852 ms | retrieve 82 ms | generate 210,779 ms | total 217,717 ms`, tokens
`prompt 1,253 / completion 2,087`. Generation is 97% of the wall clock; retrieval is 0.04%. The
completion count is mostly reasoning tokens (think:true), ~10 tok/s, matching the §14 CPU
measurement. Recorded in LEARNINGS section 18 and used to seed RAG-MASTERY section 8.

### Not built

No metrics or alerting, no aggregate latency view, no export to CloudWatch-shaped tooling, and the
per-answer panel is the only UI. Scorecard row 6 was scored 2 on that basis: this is evidence
capture, not observability.

---

## Record search - metadata shape (2026-08-06)

Spec: `docs/superpowers/specs/2026-08-06-record-search-design.md`.
Plan: `docs/superpowers/plans/2026-08-06-record-search.md`.

### Metadata is nested, not flat dotted keys - a deviation from the spec

The spec wrote metadata keys as flat dotted paths (`"customer.name"`, `"_confidence.min"`).
**Qdrant parses `.` in a filter key as a nested-path separator**, so a payload key that literally
contains a dot can never be matched by a filter, and the two stores would disagree about what a
path means. The stored shape is therefore three nested trees:

```json
{
  "values": { "customer": { "name": "ACME" }, "issueDate": "2026-05-02" },
  "prov":   { "customer": { "confidence": 0.82, "page": 2, "bbox": [12,44,90,60] } },
  "conf":   { "min": 0.71, "avg": 0.88 }
}
```

Filter paths stay dotted in the API (`values.customer.name`, `conf.min`) and each translator
splits them: Postgres `metadata #>> '{values,customer,name}'`, Qdrant `values.customer.name`.
Array markers `[]` are dropped from a path, because an array element is its own chunk carrying its
own scalars - `lineItems[].sku` becomes `values.lineItems.sku`.

### chunks carries the metadata, not a side table

`chunks.metadata JSONB NOT NULL DEFAULT '{}'` plus `chunks.doc_type`. Denormalized per chunk on
purpose: every filter then becomes a predicate inside the retrieval query with no join, which is
what keeps the filter enforceable in all six backends rather than a post-filter on results.
GIN index uses `jsonb_path_ops` (containment only, smaller and faster than the default operator
class - none of the filters need key-existence search).

### ingestChunks fails loudly on a metadata/chunk size mismatch

`capToBudget` can split one rendered block into several chunks and renumbers the whole list. A
metadata list built against the pre-cap list would then attach one field group's provenance to
another group's text - silent, and unfindable later. The 8-arg `ingestChunks` overload throws
`IllegalStateException` when the sizes differ, and callers that pass metadata must run
`capToBudget` themselves first.

### Environment note

The dev stack (`docker compose up -d`) must be running for the tests that boot against
`application.yml` rather than Testcontainers - `GraphPropertiesTest` is the one that fails first
with "Connection to localhost:5432 refused" when it is not.

### Metadata keys are paths, not leaf names - a bug found live, not by tests

The first renderer stored each field under its **leaf name**: a line item's SKU landed at
`values.sku` while every filter addresses `values.lineItems[].sku`. All 277 tests passed, because
every one of them filtered on a top-level field. The first live query against a line item returned
nothing.

Fix: `RecordRenderer.putPath` stores values and provenance nested under the full path, and every
non-header block inherits the record-level scalars so "ACME invoices whose line item is B-2" can be
answered by the line-item chunk itself. The array index is dropped from the metadata path
(`lineItems[3].sku` -> `values.lineItems.sku`) because each element is already its own chunk, so a
filter path must not depend on which element matched. `RecordRendererTest` gained four cases that
would have caught it.

### Confidence policy

Every field is indexed regardless of its score. A `min-confidence` threshold at ingest was
considered and rejected: a dropped field is a question nobody can ever answer, and the miss is
invisible from outside. Confidence is exposed instead - per field under `prov`, aggregated per
chunk as `conf.min` / `conf.avg` - so a threshold becomes a caller's filter. A field with no
reported confidence gets no key at all: 0 would hide it from every threshold filter and 1.0 would
be a fabricated guarantee. Non-numeric confidence ("high") is quarantined as `confidence_raw` so it
cannot poison a numeric range filter.

### Two hashes

`content_hash` covers the rendered text and drives re-embedding; `raw_hash` covers the raw record
and drives a metadata-only refresh. Without the split, a re-extraction that jitters a confidence
from 0.82 to 0.83 would re-embed a whole corpus to produce byte-identical vectors. Verified live:
identical record -> `skipped`, confidence-only change -> `metadata-refreshed` (zero embedding
calls), value change -> `indexed`.

### Delete ordering fixed while here

`IngestService.delete` deleted Postgres rows before Qdrant points - the opposite of the rule
`LEARNINGS.md` §13 states. Now Qdrant goes first, so a Qdrant failure leaves the Postgres rows
intact and retryable instead of orphaning vectors forever. Delete also now clears `doc_edge` rows
where the document is the **destination** (a dangling inbound edge lets graph expansion hop to a
document that no longer exists) and the `document` registry row. `chunk_feedback` and `rag_trace`
are deliberately kept.

### Qdrant limits that shaped the design

- **Dots in payload keys are path separators.** Flat dotted metadata keys would match in Postgres
  and never in Qdrant, so metadata is stored as nested `values`/`prov`/`conf` trees and each
  translator splits a dotted filter path its own way.
- **`Range` is numeric only.** A `date` range on the qdrant backend throws
  `IllegalArgumentException` rather than silently not applying. Storing dates as epoch numbers is
  the follow-up if it matters.
- **`is_null` is the inverse of `exists`,** so `exists` conditions go into `must_not` rather than
  `must`.

### Accepted gaps (deliberate, from the plan's self-review)

- **Filter warnings are not surfaced per request.** An unknown filter path matches nothing rather
  than returning a warning, because a warnings envelope on `/search` would break the response
  contract the existing UI depends on.
- **`RenderProfile.boundaries` is parsed, stored, and tested but not yet consumed** by the
  renderer, which uses generic boundaries only. The field is persisted so wiring it later needs no
  migration.
- **No UI for records or filters.** The endpoints are curl-level; the existing screens are
  unchanged.
- **The latency measurement is not evidence.** Filtered vs unfiltered on a 3-record project
  (qdrant 25 ms -> 7 ms, hybrid 4 -> 4) is noise at that size. A real number needs records at wiki
  scale.

### Live verification (2026-08-06)

Ran against the app on port **8086** rather than the usual 8085: an app instance from the previous
session still held 8085, and starting a second one on a free port was preferable to killing a
process the user started. Project 12 in the live database holds the verification records
(`INV-5575`, `INV-5576`, `DN-9001`); projects 10 and 11 are earlier attempts from the same session
and can be deleted.

Confirmed live: the three ingest outcomes; `docType` narrowing; `values.customer` eq; `conf.min`
range excluding the 0.44-confidence document; `values.lineItems[].sku` selecting exactly the
`lineItems[1]` chunks; and stored metadata carrying `page`/`bbox` in `prov` while the embedded text
contains neither.

## Query understanding (2026-08-07)

Plan: `docs/superpowers/plans/2026-08-06-query-understanding.md`, 9 tasks, executed inline.
Spec: `docs/superpowers/specs/2026-08-06-query-understanding-design.md`.

### The facet catalogue is derived, never declared

`FacetRepository.facets` walks `chunks.metadata` with a recursive CTE and returns every leaf path
under the `values` and `conf` trees, with sample values and a distinct count. `prov` is excluded on
purpose: provenance is filterable, but nobody asks a question about a bounding box.

Three things about that SQL are worth knowing before touching it:

- **The paths it returns are already in the shape a filter uses.** The recursion joins segments
  with dots and the stored metadata has no array level, so it yields `values.lineItems.sku` - which
  is exactly what `FilterSql.segments` produces from the API path `values.lineItems[].sku`. A facet
  is therefore always filterable; the two can't drift.
- **The sample limit is interpolated, not bound.** A bind parameter is not allowed inside an array
  slice `[1:n]`, so the value is clamped to 1..20 in Java first and then formatted in.
- **Deviation from the plan:** the seed term guards with `jsonb_typeof(r.node) = 'object'` instead
  of the plan's `r.node IS NOT NULL`. `jsonb_each` errors on a scalar, so a tenant whose `values`
  is not an object would have taken down the whole catalogue query rather than contributing
  nothing.

Access labels apply to the catalogue like any other read, and the cache key includes the caller's
groups. A facet is data about data: listing one the caller cannot read is still a leak.

### Model output is validated, not trusted

`ExtractionValidator` rebuilds the model's JSON through `MetadataFilter.parse`, so extraction can
only ever express what the DSL already allows and can never reach the access-label term. Unknown
paths, unknown docTypes, oversized values and malformed conditions are dropped with a reason that
goes into the trace. A hallucinated field is the expected case, not the exceptional one.

The facet type decides the cast, not the model - the type is derived from the data, and a mixed
column degrades to `text` rather than producing a cast error at query time.

### An explicit caller filter wins outright; it is never merged

Merging would let a model silently narrow a scope the caller deliberately set. When a caller
supplies a filter, extraction does not run at all - no model call, no `understand` stage in the
trace.

### Widen on empty

If the filtered retrieval returns nothing and the filter was non-empty, retrieval runs once more
unfiltered and the response says `widened: true`. A mis-extracted value costs one extra query
rather than becoming a confident "not found in knowledge base" - the failure mode that hides
documents from people.

The trace records the filter that was ATTEMPTED plus the widen flag, not the filter that ended up
applying. "Why did it not find my document?" is answerable only if the wrong filter is still there.

### Deviations from the plan

- **`RagTrace` and `ChatService.StreamOutcome` kept a convenience constructor** at the old arity
  instead of updating every existing construction site. The plan expected a compile break in
  `TraceControllerTest` and `AskServiceTest`; a secondary constructor delegating with
  `(null, false)` says the same thing in one place.
- **`ChatProvider.chat(system, user, model)` was added** because `OllamaChatProvider` hardcoded
  `props.getModel()`, so `app.understand.model` would have been read and then silently ignored. The
  default implementation ignores the model name, so a provider that cannot switch models per call
  stays valid.
- **`FilterJson` is new and was not in the plan.** Jackson's default view of `MetadataFilter` emits
  `conditions` and an `empty` flag, which is not what `MetadataFilter.parse` reads. A client must be
  able to echo the reported filter straight back as an explicit one, so the two shapes have to
  match.
- **`ChatService.chatStream` gained an `onFilter` callback** rather than reporting the filter in the
  returned outcome. The frame has to reach the client BEFORE the tokens: once the answer is
  streaming, "by the way, I narrowed your search" arrives too late to change how it is read.
- **The golden set has no hand-listed doc ids.** `RecordGroundTruth` computes which corpus records
  a question should match by evaluating its expected filter against the RAW record. Listing ids
  across 210 generated records would be a transcription exercise that goes stale the moment the
  generator changes, and ground truth that reuses the code under test proves nothing.

### Two bugs found while building, both by tests that existed for that purpose

- **The ground-truth matcher unwrapped the wrong objects.** It treated any object with a `value`
  key as an extraction wrapper - but the contract records carry a real business field called
  `value`, so every contract looked like a wrapper and every other contract field became
  unreachable. Production's `ValueWrapper.detect` was already stricter (a value key AND nothing else
  but provenance); the matcher now mirrors it. The corpus keeps that field precisely because it is
  the adversarial case.
- **One golden question matched nothing.** "overdue invoices for Umbrella SA in March 2026" is a
  three-way conjunction that has no record in the generated corpus. Changed to February, which has
  exactly one (`INV-0045`). A golden entry matching zero documents measures nothing but its own
  typo, which is why `RecordGroundTruthTest` asserts every filter question matches something.

### The prompt layout was load-bearing, and the eval is the only thing that saw it

The first eval run (2026-08-07, 41 min, 15 live `qwen3:4b` calls) reported **condition recall
0.07** - 1 matched condition out of 15 expected - while docType accuracy was a perfect 13/13. That
looked like "a 4B model cannot extract filters". It was not.

The prompt listed each facet as a pipe-delimited row, straight from the plan:

```
- invoice | values.customer | text | examples: ACME Corp, GLOBEX Ltd
```

Nothing in that row says which column is the path. Probing the model directly with that exact
prompt (one call, 60 s, instead of another 41-minute run) returned:

```json
{"docType": "invoice", "filters": [{"path": "invoice | values.customer", "op": "eq", "value": "ACME Corp"}]}
```

It copied the whole row into `path`. `ExtractionValidator` then dropped every condition as an
unknown path - correctly, and silently, because a dropped path is the expected case.

Fixed by naming each field where it appears, grouping by document type so the type never shares a
line with a path, and adding a worked example of the exact output shape:

```
docType: invoice
  path: values.customer   type: text   examples: ACME Corp, GLOBEX Ltd
```

Re-probed: paths correct on all four sample questions, including the two-condition one.
`QueryUnderstandingPromptTest` now pins the layout - no docType on a `path:` line, one `docType:`
heading per group, and the worked example present.

**Second bug the same probe found.** For "invoices over 5000" the model returns
`{"op":"gt","value":5000}`, but the DSL wants `{"op":"range","gt":5000}`. `gt` is not a valid op, so
`MetadataFilter.parse` threw and the condition was dropped as malformed. `ExtractionValidator`
now normalises bare comparison ops (`gt`/`gte`/`lt`/`lte`) into a `range` bound before validating.
The prompt still describes the canonical shape; the normaliser is the control, following the same
rule §5 injection hardening established - a prompt is a request, code is a control.

> Lesson: **35 unit tests passed through both bugs.** Every validator test feeds hand-written JSON
> that already has the right path and the right op, so they test the validator against the same
> mental model that produced the prompt. Only a real model against a real corpus produced the
> malformed input. This is `LEARNINGS.md` §19's lesson again in a new place: the eval is not a
> formality after the feature, it is the first thing that does not share the code's assumptions.

Second consequence, cheaper to state than to discover: **an eval that prints nothing until it
finishes cannot be distinguished from a hung one.** The eval now prints a line per question with
its latency as it goes.

### A pre-existing SQL bug the eval flushed out (2026-08-07)

The second eval run died at question 3 with:

```
ERROR: operator does not exist: timestamp with time zone >= character varying
(metadata #>> '{values,issueDate}')::timestamptz >= ?
```

`FilterSql` cast the COLUMN to `timestamptz` and left the bound parameter a string. This is a defect
in **record search** (commit `b60ba56`), not in query understanding - it was simply unreachable
until something produced a `date` range, and every filter written by hand until then compared text.

Worse, `FilterSqlTest.dateRangeCastsToTimestamp` asserted `"::timestamptz >= ?"` - it **encoded the
bug as the expectation**. A unit test that asserts a generated SQL string can only check that the
string matches what its author believed; only the database can say whether it is valid.

Fixed in `FilterSql`:
- the bound side gets the same cast as the column side (`?::timestamptz`, `?::numeric`);
- `eq` and `in` bind their value as text, because `metadata #>> path` always yields text and a JSON
  number arriving as a Java Integer would render as `text = integer` - the same type error, one
  question further down the golden set.

Both are now covered by tests that EXECUTE against Postgres
(`MetadataFilterIntegrationTest.aDateRangeActuallyRunsOnThePostgresBackends` and
`aNumericEqRunsEvenThoughTheStoredValueIsText`) rather than asserting SQL text.

`FilterQdrant` is unaffected: it already refuses a `date` range loudly.

### Eval results and what they are worth (2026-08-07)

Three runs, each ~30-40 minutes because extraction is one live `qwen3:4b` call per question on CPU:

| | run 1 | run 2 | run 3 |
|---|---|---|---|
| outcome | completed, bad numbers | **crashed at Q3** | completed |
| condition precision | 0.50 | - | 0.79 |
| condition recall | 0.07 | - | 0.73 |
| recall@5 with / without | 0.82 / 0.64 | - | 1.00 / 0.64 |
| what it found | the prompt-layout bug | the `FilterSql` date-cast bug | the honest baseline |

Read the numbers with two caveats stated in the report header and in `LEARNINGS.md` §20: embeddings
are FAKE, and recall@5 = 1.00 is near-tautological because ground truth is defined as "records
matching the expected filter". They establish that the filter mechanism works end to end, nothing
about semantic retrieval quality. The informative figure is the **0.64 unfiltered baseline**.

Still missed, all real: the `in` operator is never produced (`open or overdue`), `conf.min` is never
inferred from "high confidence", and one free-text question got an invented filter (widening caught
it). One golden expectation was itself wrong - the deliberate typo `ACEM Corp` was silently
corrected by the model to `ACME Corp` rather than widening, which the prompt explicitly asks for.

**Not done, deliberately:** no baseline file and no regression gate for this eval. It reports. That
is the same order drill C followed - measure first, gate once the numbers are trusted - and it is
recorded in `ROADMAP.md` as the remaining half of the frozen-corpus item.

## Closing the extraction gaps + the records eval gate (2026-08-07, later)

### The two gaps, diagnosed by probe rather than by another 30-minute run

Two live calls with the real generated prompt (dumped from `buildPrompt`, not retyped) explained
both misses in seconds:

| question | model returned | why it scored zero |
|---|---|---|
| `invoices that are open or overdue` | `{"op":"in","value":["open","overdue"]}` | list under `value`, not `values` - `MetadataFilter.parse` sees an empty `in` list and throws |
| `only high confidence invoice data` | `{"path":"conf.avg","op":"gt","value":"0.8"}` | picked a different aggregate than the golden set assumed |

Fixes:
- **`ExtractionValidator.normalizeOp` also moves an `in` list from `value` to `values`.** The intent
  is unambiguous, so it is honoured rather than dropped. A single scalar under `value` is still
  dropped - there is no unambiguous intent to recover there.
- **The prompt shows an `in` example.** Models copy an example far more reliably than they follow a
  prose rule; the ops were listed in the shape line all along and that was not enough.
- **The prompt explains `conf.*`.** These are computed at ingest, so their meaning exists nowhere in
  the data and no amount of sample values conveys it. The note says conf.min is the least confident
  field and is what "high confidence" usually means - a record is only as trustworthy as its
  weakest extracted field.

### One golden question was changed, and why that is not cheating

`only high confidence invoice data` names no threshold. The model's 0.8 and the golden's 0.7 are
equally defensible, so the entry scored taste rather than mechanism - it was an invalid test item
regardless of which way it came out. Reworded to `invoices where the minimum extraction confidence
is at least 0.7`. Choosing conf.min over conf.avg is still the model's job and still scored. The
reason is committed as a comment in `records-golden.yaml` so a future reader can disagree with it.

### Measured after the fixes

| | before | after |
|---|---|---|
| condition precision | 0.79 | **0.81** (13/16) |
| condition recall | 0.73 | **0.87** (13/15) |
| docType accuracy | 1.00 | **1.00** (13/13) |
| no-filter left unfiltered | 1/2 | 1/2 |
| recall@5 with / without | 1.00 / 0.64 | 1.00 / 0.64 |

**docType went DOWN, and the cause is not a defect.** The one miss is `invoices for ACEM Corp`, the
deliberate typo: the model now abstains completely (no docType, no conditions) instead of guessing
`invoice`. Abstaining on a name it cannot match is arguably the better behaviour, and it is what the
prompt asks for. It leaves that entry's `expectWiden` unmet - there is nothing to widen from - so
the widen path is still exercised only by the over-extraction case.

Still open and now baselined rather than fixed: **1 of 2 free-text questions gets an invented
filter** (`anything mentioning late payment` extracts a status). Widening catches it. Treating it as
acceptable is a decision, not an oversight, and the gate holds it at exactly 1 so it cannot get
worse silently.

### The gate

`RecordFilterEvalTest` now compares against `src/test/resources/eval/baseline-records.yaml` and
fails on regression. Deliberately NOT reusing the wiki gate's classes: `EvalBaseline` is six
retrieval backends of (recall@5, MRR, hit@1), and packing extraction precision into a field called
`recall5` would have saved a hundred lines at the cost of every future reader. New, parallel:
`RecordEvalBaseline`, `RecordEvalComparison`, `RecordEvalBaselineStore`.

Rules, all unit-tested offline in `RecordEvalComparisonTest` (12 tests, milliseconds - the eval
itself is half an hour, so the gate logic must never need it):

- **Floor, not pin.** Improvement never fails.
- **Tolerance 0.05**, wider than the wiki gate's 0.02. Retrieval there is deterministic; extraction
  here is a sampled model that drifts between runs and moves with a version bump. A gate that cries
  wolf ends with someone passing `-Deval.baseline.update=true` to silence it, which is worse than no
  gate at all.
- **Per-question filter tracking.** A question that used to produce a filter and now produces none
  fails even when every aggregate stays inside tolerance - that is exactly how the prompt-layout bug
  presented, and an aggregate over 15 questions can absorb one loss.
- **Over-extraction has no tolerance.** `noFilterCorrect` is a count; one lost no-filter question is
  a failure.
- A **corpus seed change** reports one stale-baseline cause instead of a dozen downstream failures.
- A question **added** to the golden set is a printed notice, never a failure; a question
  **removed** is an edit, not a regression.

Regenerate with `-Deval.baseline.update=true`. The run prints "review the diff before committing it,
a baseline is a claim about what is correct" - because it is, and the first baseline written here
enshrines a known over-extraction as acceptable.

### The gate failed on its own noise, and the fix was a product fix

Verifying the gate mattered more than building it. Re-running the eval with **zero code changes**
failed it:

```
[extraction] condition precision 0.750 is below the floor 0.774 (baseline 0.824 minus tolerance 0.050)
[extraction] condition recall    0.800 is below the floor 0.883 (baseline 0.933 minus tolerance 0.050)
```

Two identical runs differed by **0.13 on condition recall** and 0.07 on precision (docType went the
other way, 0.92 -> 1.00). That is pure model sampling.

The obvious response - widen the tolerance to ~0.15 - was rejected. It would have swallowed the
noise and the regressions worth catching in the same gulp, leaving a gate that only fires on
catastrophes like the 0.66 prompt-layout drop.

**The real defect was not in the gate.** Turning a question into a filter is a structured decision
with a right answer, and sampling means the same question narrows the corpus differently on two
consecutive asks - which a user experiences as the search being broken, not as variety. Extraction
now runs at `temperature 0` with a fixed seed:

- `ChatProvider.Options(model, temperature, seed)`, every field optional and the default
  implementation ignoring all of them, so a provider that cannot vary settings per call stays valid.
- `OllamaChatProvider` sends them under Ollama's `options` key **only when set**, so an ordinary
  answer keeps the model's own defaults instead of silently becoming greedy. Both directions are
  asserted in `OllamaChatProviderTest`.
- The old `chat(system, user, model)` overload delegates, so nothing else changed.

Deterministic baseline: condition precision **0.813**, recall **0.867**, docType **1.00**, and
extraction p50 dropped 66 s -> 54 s as a side effect of not sampling.

> Lesson: a flaky gate is usually telling you something true about the system, not about the gate.
> The temptation is to widen the tolerance until the noise fits inside it; the noise was a real
> behaviour a user would have hit.

Cost of getting this right: five eval runs of roughly half an hour each. Three found bugs, one
wrote the baseline, one verified the gate. Budget that, or do not build a gate.

### Gate verified (2026-08-07, final)

Two consecutive runs under determinism produced **identical** metrics - condition precision 0.813,
recall 0.867, docType 1.00, 13 matched of 16 extracted - and the second run passed the gate:
`Tests run: 1, Failures: 0` / `BUILD SUCCESS`. The 0.05 tolerance is now honest rather than
hopeful, and it is there for genuine drift (a model upgrade, a prompt edit), not for sampling.

Numbers quoted anywhere in the docs are the deterministic ones. An earlier draft of this file said
condition recall reached 0.93; that was a sampled run and is superseded - exactly the trap the
determinism work exists to close.

---

## 2026-08-08 - Query routing and the cheapest correct path

Spec `docs/superpowers/specs/2026-08-08-query-routing-design.md`, plan
`docs/superpowers/plans/2026-08-08-query-routing.md`, executed inline. Three routes on the answer
path: chit-chat answers from a fixed string, aggregate answers a count from SQL, search is
unchanged. Aimed at `RAG-MASTERY.md` row 4 (stuck at 2 because "routing does not exist") and row 8
(no latency lever ever pulled).

### Decisions taken while planning, not in the spec

- **`QueryRouter.route(String question)`** takes only the question. The spec sketched
  `(SearchContext, projectIds, question)`; routing needs neither, and an unused parameter reads to
  the next person as if it mattered.
- **No `route_latency_ms` column.** `rag_trace.stage_latency_ms` is JSONB whose own javadoc says a
  new stage needs no migration, so route latency goes in there under `route`. Only `route` itself
  became a column, because that is the field rows get filtered by.
- **`ChatService` gained a fifth overload** rather than a `Signals` refactor. The file already
  delegates down a chain of overloads and `ChatServiceTest` is 14 KB; collapsing them is unrelated
  churn. Debt, noted: if a sixth is ever needed, collapse them all.

### Rules decide chit-chat only, never aggregate

A `how many` keyword cannot separate "how many invoices do we have" (a count) from "how many days
do I have to pay an invoice" (a payment-terms clause). A misroute produces the wrong SHAPE of
answer, so the fuzzy half stays with the model. The free half - blank input and a fixed greeting
list, matched whole-string so "thanks for the invoice policy" is not a greeting - stays free.

### The bug the unit tests could not see, again

The design said: run the router with `think:false` and a small `num_predict`, and read the label
with a keyword scan. Fifteen unit tests passed on that design. A one-minute probe against the real
qwen3:4b killed it:

```
[3997 ms] 'how many invoices for ACME Corp' -> 'We are given the user's message: "how many
          invoices for ACME Corp"\n\nWe need to classify it into exactly one route: chitchat,
          aggregate'
```

With `think:false` the model reasons **in `content`** (`LEARNINGS.md` §12 again), and the 32-token
cap ran out before it ever chose. Worse, the truncated text contains the option list, so a
first-keyword-wins scan would have routed nearly every question to **chitchat** - the route that
answers nothing. Confidently wrong on every question, with a green suite.

**Fix, measured rather than argued.** Three variants against the live model, 8 probe questions:

| Variant | Accuracy | Mean latency |
|---|---|---|
| `think:false`, no schema, cap 32 | unusable - never answered | ~4 s |
| `think:false` + JSON schema, cap 16 | **8/8** | **3.4 s** |
| `think:true` + JSON schema | 8/8 | 44 s (one question 206 s) |

So `ChatProvider.Options` gained a nullable `responseSchema`, forwarded as Ollama's `format`, and
the router constrains the reply to `{"route":"<enum>"}`. `think:true` is correct but costs more
than the extraction call routing exists to skip.

`Route.parse` also changed: a reply naming **more than one** route is now unreadable rather than
resolved by position, and unreadable means SEARCH. Ambiguity resolves to today's behaviour instead
of to a confident guess.

> Lesson, third time in this repo: the prompt is an interface, and the only test of it that counts
> runs against the real model. Unit tests pin what you decided; they cannot tell you the model
> ignores it.

### Aggregate route

`RecordCountRepository` reuses `DocFilter.groupClause` and `FilterSql.render` - one copy of the
access-control predicate, not two - and counts `DISTINCT doc_id`, because one record renders to
several chunks. The sentence is built in code by `AggregateAnswerer`: a model asked to count from
retrieved context is guessing from a sample, and a model handed the right number is a chance to
change it.

**Aggregate never widens.** Zero is frequently the true answer to a count, and widen-on-empty would
replace a correct 0 with a number nobody asked for. Instead the applied filter is printed beside
the count, so `0 invoice records match where values.customer = ACEM Corp` shows the typo. A failing
count falls through to the search path rather than surfacing an error.

### Visibility

`route` NDJSON frame before the `filter` frame and before any token; `rag_trace.route`; chips above
each answer in the chat UI. The chip work also fixed the `filter` frame, which had been emitted
since 2026-08-07 and silently dropped - `app.js` handled `reasoning`, `token`, `sources`, `trace`,
`guard`, `error` and nothing else, so every "I narrowed your search" notice went nowhere.

### Measured result (2026-08-08)

Baseline run over the 21-question golden set, quiet machine, `qwen3:4b`, fake embeddings:

```
route accuracy        1.00   (21/21)
aggregate counts      4/4 exactly right
router p50            1,437 ms
extraction p50        52,613 ms
condition precision   0.89   condition recall 0.89   docType 1.00
no-filter questions   4/4 correctly left unfiltered
recall@5   with extraction 1.00   without 0.57
```

The ratio is the point: **1.4 s to decide whether to spend 52 s.**

**Prompt tuning with a control.** v1 scored 20/21 - "delivery notes shipped by Speedy Freight" is a
bare noun phrase and the model called it small talk. v2 added a bare-phrase rule and one
noun-phrase example: 21/21. Because that is tuning against the eval, nine held-out questions (none
in the golden file) were run against both: 9/9 either way, so the change closed a real gap rather
than memorising one question. `QueryRouterPromptTest` fails if an example ever copies a golden
question verbatim.

**Unexplained-but-better:** "anything mentioning late payment" and "invoices for ACEM Corp" both
stopped producing a filter, moving `noFilterCorrect` from 1-of-2 to 4-of-4, with no change to any
extraction code. Both are improvements, but nothing here caused them.

Two consecutive runs then matched exactly on every metric and the second passed the gate, so the
2026-08-07 determinism claim holds *within a machine state* and drifted *across weeks*. Recorded
rather than smoothed over - and it is the argument for a tolerance-based gate over an equality
check.

**Housekeeping finding worth more than it sounds.** Mid-session Ollama took **256 s for a 10-token
reply**. Cause: four orphaned JVMs and six orphaned containers left by killed eval runs, taking the
box to 2.9 GB free with 1.5 GB in memory compression. After cleanup: 3.5 s. A 70x latency swing
from process hygiene - any latency number quoted without the machine state is noise.

**Process note.** Killing a 30-minute eval to iterate on the prompt with a 90-second standalone
probe (21 questions straight at Ollama, no Spring, no containers) was worth far more than the run
it interrupted. Build the cheap loop first; spend the expensive one on the baseline.

### The live smoke found what a 21/21 eval could not (2026-08-08)

With the gate green and every metric at its best value, a manual smoke against the running app
asked *"what is the total on invoice INV-5575"* and got:

```
route=aggregate | sources=0 | 1 invoice record matches where values.invoiceNumber = 5575.
```

A factual lookup answered with a record count. No golden question distinguished "count the records"
from "read a value out of a record", so a perfect score on the eval said nothing about that whole
category. Six probes written for it scored 3/6; a prompt rule separating record counts from values
stored inside documents took it to 5/6 while golden stayed 21/21 and held-out stayed 9/9. The same
live question now answers `The total on invoice INV-5575 is 1899.5 [1]`.

Accepted limitation, documented in `QueryRouter`: "how many packages are on delivery note DN-9001"
still routes to aggregate. It counts things inside a single document - neither a record count nor a
lookup.

Also worth noting for the next person: `spring-boot:run` in the background survives killing the
Maven wrapper. A re-smoke that "shows the fix did not work" may simply be talking to the old JVM
still holding the port - check `netstat -ano | grep :PORT` before believing the result.

---

## 2026-08-11 - injection hardening (quarantine, streaming guard, groundedness judge)

Spec: `docs/superpowers/specs/2026-08-11-injection-hardening-design.md`.
Plan: `docs/superpowers/plans/2026-08-11-injection-hardening.md`. Commits `c705cfa`, `1526038`.

### Decisions that were not in the spec

**The scan moved from the callers to the funnel.** The spec put it in the two ingest controllers
(§1.3). A review found `POST /ingest` and `POST /projects/{id}/import-wiki` reaching the index
without meeting it - two of four doors locked. It now lives in `IngestService.ingestChunks` and
throws `QuarantineRequiredException`; callers that know the document's original form catch it and
store that form in the pen, callers that do not fail loudly. This is a deviation from the written
design, and the design was wrong.

**`QuarantineService` was invented to own the ordering.** The spec had each caller hold the
document itself. Four call sites meant four chances to get the order backwards, and the order is
the whole safety property: un-index first, THEN record the hold. The other way round, a Qdrant
outage commits the pen row, fails the delete, and leaves an operator reading "contained" about a
document that is still searchable.

**The credential rule was got wrong twice before it was right.** v1 flagged any value after a
keyword, so "the password is expired" was a finding. v2 required the value to look secret-shaped -
a digit, a separator, or 20+ characters - which silently dropped `the recovery code is swordfish`
and still fired on "credentials are role-based". v3 inverts the question: everything is a secret
unless it is recognisably prose, where prose means an enumerated word OR a lowercase -ed/-ing
participle. The participle rule was found by writing a test for "the token island-hopping strategy
is documented" - with a 40-character window the regex skipped ahead to a later "is" and took
"documented" as the value.

**The default label is `securityProps.getDefaultGroup()`, not `"public"`.** With a different
configured default, a hardcoded `"public"` produces a held document that `resolveGroups` then
rejects on release - permanently unreleasable.

### Tradeoffs taken

- **High recall over precision on the scanner**, chosen explicitly. Documents that genuinely
  contain a credential string get held, including four of this repo's own docs (they quote the
  drill's `recovery code is hunter2`). Release is one call. The alternative missed real passwords.
- **A password ending in -ed or -ing is missed.** Stated in the javadoc rather than left to be
  discovered.
- **Streaming holds tokens** until the first valid citation - a blank pane for roughly one sentence
  at this box's ~10 tok/s. The `verifying` frame exists so that pause is legible.
- **The groundedness judge ships off.** It is code nobody has switched on, and saying so is more
  useful than a default that looks finished.

### Known gaps, deliberately not fixed here

- **Release has no privilege gate and no audit row.** Any authenticated user in `public` can undo
  the one blocking control, and the pen row is deleted, so who released what is unrecoverable. This
  is a hole in the spec (§1.4 asked only for group scoping), not a deviation from it.
- **A release that fails mid-ingest** leaves a document both held and partially indexed. Nothing in
  this codebase is transactional across Postgres and Qdrant, and `ingestChunks` has always had this
  window for ordinary re-ingest.
- **`RecordRequest.metadata`** (caller-supplied) is not scanned. It is filterable and feeds the
  facet catalogue, though it is not returned by `SearchHit` or `ChunkView`.
- **The judge's false-refusal rate is unmeasured**, so its default cannot honestly move yet.

### Tests that had to change, and why that is the feature

`InjectionDefenceTest.aStreamedInjectionIsReportedBecauseItCannotBeRecalled` asserted that
`hunter2` REACHED the user and that the verdict was the only mitigation. It is now
`aStreamedInjectionIsNeverSentAtAll`. The old name is quoted in the new test's javadoc so the
history of the hole is not lost.

`ChatServiceTest`'s stub streamed "Hello there" with no citation, which the emitter now refuses.
Adding a citation kept those tests about condensation, widening and the count fallback instead of
accidentally about the guard.

The most useful test finding: every "never indexed" assertion checked Postgres only. Deleting the
Qdrant delete call left all sixteen green while the secret stayed retrievable through the qdrant,
hybrid and rerank backends - the `LEARNINGS.md` §13 bug class this feature cites as its precedent.
`assertNowhereIndexed()` now checks Postgres, Qdrant and the `document` registry.

### Verification

Full suite **468 tests, 0 failures, 3 skipped** (from 415). Drill on demand:
`./mvnw test "-Dgroups=eval-injection" "-DexcludedGroups="` -> 2/2, confirmed absent from the
normal build.

Live smoke on :8091 (partial - stopped before the stream check): the drill page uploaded returned
`quarantined: true, chunksStored: 0` with `recovery code = ***`; a hybrid search for `hunter2`
returned five hits, none containing it; `GET /quarantine` listed the page with masked findings and
no raw text. **Not verified live:** the `verifying` frame order, and the UI's "Checking sources"
state has never been looked at in a browser.

One trap worth repeating: the first smoke attempt started on :8088, failed with "Port 8088 was
already in use", and something DID answer there - a VS Code extension's JVM. Confirm the process on
the port is the one you just started before believing any smoke result.

### Live verification, completed 2026-08-11 (second attempt)

The `verifying` frame was checked on a real stream, which the first attempt never reached. Frame
order on the search route, against the live stack:

```
route -> verifying -> token -> sources -> trace -> done
```

The answer came back cited (`... returns "all backends side by side (scores + timing) ..." [1]`),
so the guard passed and no `guard` frame was emitted. That is Task 8's server side verified. The
browser-side "Checking sources" state has still never been looked at by a human.

Two things worth carrying forward from that run.

**A router miss the gate cannot see.** `what is chunking` routed to **chitchat** and was answered
with the canned reply; `what is chunking and why does it matter` and `what is RRF` both routed to
search. Recorded in `ROADMAP.md`. Nothing in this change touched routing - it is a pre-existing gap
that only a live question could surface, exactly like the `INV-5575` miss on 2026-08-08.

**The machine was starved and the probes stopped being trustworthy.** Firing five stream requests
in a loop and reading only the first frame of each left their generations running; free memory fell
to **829 MB of 16 GB**, and a call that had returned its route frame in ~1.4s stopped returning
anything within 60 seconds (`curl exit=28`, zero bytes). `LEARNINGS.md` §21 already records the
3.5s-vs-256s version of this. The practical rule: one live probe at a time, and check free memory
before believing any timing.

---

## Quarantine release control (2026-08-12)

Spec/plan: `docs/superpowers/{specs,plans}/2026-08-12-quarantine-release-control*`. Executed inline,
5 tasks. Suite **465 -> 477**, 0 failures, 3 skipped.

### Decisions not in the spec, or changed while building

**The baseline test count in my own notes was wrong.** The 2026-08-11 session recorded 468 tests.
Task 1 added 3 and measured 468, which puts the real baseline at 465. The plan was corrected rather
than the measurement. Worth remembering that a count carried in prose is a claim, and this repo now
has two of them disagreeing.

**`QuarantineReleaseService` is a new class, not a method on `QuarantineService`.** The spec first
put release on the existing service, which reads better and does not compile: `RecordIngestService`
already injects `QuarantineService`, and release needs `RecordIngestService`, so the merged version
closes a constructor-injection cycle Spring refuses to start. Caught while writing the spec by
reading the constructors, not at runtime.

**`@PreAuthorize` over filter-chain path matchers.** Both work. The annotation was chosen so the
rule is attached to the method rather than to a URL shape - a later path rename disarms a matcher
with no compile error and no failing test. Cost: `@EnableMethodSecurity` is now on globally. Nothing
else in `src/main/java` carries a method-security annotation, so its only effect is these two.

**Discard is gated as well as release,** which the original ROADMAP entry did not ask for. Release
is reversible in the sense that the document ends up indexed and can be deleted again; discard is
not. Once a document is un-indexed into the pen, the pen holds the only copy, so an unprivileged
discard destroys both the document and the evidence of the attack.

**The audit repository's round-trip test lives in `QuarantineIntegrationTest`,** not in its own
class as the spec's §3.4 said. It needs a real Postgres, and spinning a second pgvector + Qdrant
container pair for two assertions costs more than it proves. The spec was corrected.

### Tradeoffs accepted

- **No audit read endpoint.** psql is the reader. The endpoint carries a real question - a doc id
  plus a principal is not nothing, even with the findings masked - and guessing at the scoping rule
  was worse than deferring it. In ROADMAP.
- **The mid-ingest partial state is visible, not fixed.** A release that dies part way leaves a row
  reading `attempted`. Nothing repairs or retries it.
- **HTTP 403 is not covered by a test, but it was verified live.** The tests drive the controller as
  a bean and assert `AccessDeniedException`; the status code itself is Spring Security's default
  translation, and no test in this project drives quarantine over MockMvc. Rather than build that
  harness for one assertion, the claim was checked against a running app - see the live run below.
  It stays an untested claim in CI, which is the honest description.
- **Roles are config**, so changing who may release needs a redeploy - the same limitation already
  recorded against groups in `RAG-MASTERY.md` row 1.

### Things worth knowing next time

The `held` audit row is written **after** the hold commits, while release and discard write theirs
**before** acting. That asymmetry is deliberate and is argued in `QuarantineService.hold` and
`QuarantineReleaseService`: a row asserting containment before the un-index succeeded would be
untrue, and while the document sits in the pen the pen row already is the durable record.

`quarantine_audit` has no `raw_text` column and no foreign key to `projects`. Both are load-bearing:
the table is append-only and never pruned, so raw text there would outlive every other copy of the
credential; and the pen cascades on project delete, which is exactly when the history most needs to
survive. The no-raw-text rule is asserted by a test over `SELECT *`, so a column added later is
covered without anyone remembering to update the test.

### Live verification, 2026-08-12 (app on :8091, dev containers up, no Ollama needed)

Real HTTP against a running app, project 13 `role-gate-check-2026-08-12`:

| call | result |
|---|---|
| `alice` uploads a document containing `The admin recovery code is hunter2` | `quarantined: true`, `chunksStored: 0` |
| `haiks` `POST .../quarantine/policy/release` | **403** |
| `alice` `POST .../quarantine/policy/release` | **200** |
| `haiks` `DELETE .../quarantine/policy` (on a fresh hold) | **403** |

The audit table after that sequence:

```
 id | doc_id | action  | outcome | principal
  1 | policy | held    | ok      | alice
  2 | policy | release | ok      | alice
  3 | policy | held    | ok      | alice
```

**Neither 403 produced a row.** That is the intended shape: `@PreAuthorize` runs before the method,
so a refused call never begins a decision and therefore never records one. The audit answers "what
was decided", not "what was attempted by someone with no standing to attempt it" - if the latter is
ever wanted, it belongs in an authentication log, not here.

Then `DELETE /projects/13` was issued, which turned out to be the most useful check of the day:

| table | rows after the project was deleted |
|---|---|
| `projects` | 0 |
| `quarantine` (FK + ON DELETE CASCADE) | 0 |
| `quarantine_audit` (no FK, deliberately) | **3** |

The history survived the deletion of the project it referred to, which is exactly the case the "no
foreign key" decision exists for, and it was confirmed by accident rather than by a test written to
prove it. The three orphan rows were then deleted by hand so the dev database is clean; the app on
:8091 was stopped. Verification project 12 from 2026-08-06 was left alone.

### Cold review, 2026-08-12 (one reviewer at the unit boundary)

Twelve findings, all applied. Suite **477 -> 484**. The three that were worth the review on their own:

**A second, ungated off switch.** `DELETE /projects/{id}` has no role and no group check, and
`quarantine` cascades from it, so any authenticated user could destroy every held document with no
trace. Fixed by auditing the cascade (`ProjectService.auditPenCascade` writes one `discard` row per
held document before the delete, stamps them after); the authorisation half is deliberately left in
ROADMAP because it is project-level authorisation, not quarantine's. Worth recording that **I had
already observed this cascade live** an hour earlier and read it purely as confirmation that the
audit table's missing foreign key worked as designed.

**The gate was on the caller, not the protected method.** `QuarantineReleaseService.release/discard`
were public, took an already-resolved `Held`, and trusted `QuarantineController` to have done both
the role check and the group lookup. Any future injector of the service would have bypassed the
entire control with no compile error - the exact shape LEARNINGS §22 is about. Both checks now live
on the service methods; the controller keeps `@PreAuthorize` so a refusal costs no database work,
and its javadoc says which one is the control.

**The wiki import recorded the wrong principal for every hold.** `/import-wiki` returns a
`StreamingResponseBody`, whose body runs on a thread where the `SecurityContextHolder` thread-local
is empty, so `principalOrNull()` returned null on the path the code itself calls "most likely to
meet a real credential". The principal is now captured on the request thread and passed down through
`WikiImporter.importDir`. `principalOrNull` moved to `CurrentUser`, which is where principal
resolution belongs.

Also applied: the audit write in `hold` is logged rather than thrown (it runs inside a `catch` block
in `WikiImporter`, where a sibling `catch` cannot catch it - one audit failure would have aborted an
entire bulk import at its first held page); `catch (Throwable)` on release so an `Error` mid-ingest
still stamps the row; the final `ok` stamp is log-and-swallow because the act has already committed;
a failed stamp attaches itself as a suppressed exception rather than replacing the real failure;
`outcome()` throws when it updates no row; `CHECK` constraints on `action`/`outcome`.

Two test fixes worth naming:

- **A test that did not test its own name.** `aReleaseThatDiesMidIngestLeavesTheDecisionVisible`
  corrupted `raw_text`, which makes the JSON parse throw *before* any ingest starts - so it proved
  the stamping and never created a partial state at all. Renamed to
  `aReleaseThatFailsBeforeIngestingIsStampedFailed`, with a comment saying what would actually be
  needed. The spec's claim that the partial-state bug was "reproduced deliberately" was wrong and
  has been corrected rather than quietly kept.
- **Nothing bound `application.yml` to the constant.** Deleting or misspelling
  `roles: [quarantine-release]` would 403 every release in the running app while all 477 tests
  passed. `QuarantineControllerSecurityTest` now asserts the real configured users against
  `Roles.QUARANTINE_RELEASE`, and covers the **403 status code** that README and ARCHITECTURE
  promise - previously asserted nowhere, since the integration test calls the controller as a bean.

One thing the review cleared rather than found, worth keeping: `@EnableMethodSecurity` is inert
everywhere else in this codebase - no other method-security annotations, no `@Async`, no
`@Transactional`, no final beans - so turning it on changed nothing but the two annotations.

Schema note: the `CHECK` constraints reach an existing database through `ALTER TABLE ... DROP
CONSTRAINT IF EXISTS` followed by `ADD CONSTRAINT`, not a `DO $$` block. Spring's script runner
splits on `;` with no understanding of dollar quoting and would cut the block in half.
