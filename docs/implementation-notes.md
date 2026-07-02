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

### Real bge-reranker verification - BLOCKED by network (2026-06-19)
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
