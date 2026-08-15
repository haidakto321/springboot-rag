# Heading breadcrumb treatment - measuring what the breadcrumb is worth

Date: 2026-08-15
Status: design approved, not implemented
Supersedes: an earlier draft of this spec that proposed *adding* heading context. That draft rested
on a false premise - see "What the earlier draft got wrong".

## What the earlier draft got wrong

The first version of this design proposed prepending `headingPath` to the text handed to the
embedder, on the belief that the breadcrumb was stored but never embedded.

It is already embedded. `MarkdownChunker.java:113-116`:

```java
String headingPath = breadcrumb.isEmpty() ? null : breadcrumb;
for (String piece : pieces) {
    String text = headingPath == null ? piece : headingPath + "\n\n" + piece;
    out.add(new Chunk(text, headingPath, position[0]++));
}
```

The chunker bakes the breadcrumb into `chunk.text()` before the chunk ever reaches `IngestService`.
The `headingPath` field is a duplicate carried alongside for filtering and eval matching. So
contextual retrieval by heading path already shipped here, and the "cheap win" was already taken.

That discovery is what this spec is now about.

## Problem

The breadcrumb was added on intuition and has never been measured. Its current form is the most
verbose possible:

```
# RAG Mastery > ## Section 8 - Query routing > ### Cost of a small model

Route with a 1B model when the question is a lookup...
```

Every chunk in that section carries that identical 60-character header. Three specific reasons to
doubt it is optimal:

1. **Dilution.** A short chunk can be nearly half breadcrumb. Every chunk in a section then points
   in almost the same direction in vector space - section recall rises, within-section precision
   falls.
2. **The `#` marks are noise.** They repeat identically in every chunk of the corpus and carry no
   semantic signal for an embedding model.
3. **It leaks past the vector.** Because the breadcrumb lives inside `content`, it also feeds `tsv`
   (`schema.sql:10`), the reranker (`DjlReranker.java:77`), the answer prompt, and the UI. BM25
   double-counts heading words today. Nobody decided that; it fell out of where the concatenation
   happens.

## Goal

Make the breadcrumb treatment switchable, then measure all variants against the golden set. Produce
a number that says which form is best for this corpus - including the possibility that the answer
is "none of them, drop it".

## Non-goals

- LLM-generated context sentences. Separate, more expensive, deferred.
- Changing chunk boundaries, sizes, or the atomic-block rules.
- Adding the document name or filename to the embedded text. That is a genuinely missing signal but
  a different experiment; mixing it in would make this one unreadable.

## Modes

Config key `app.chunk.heading-style`:

All examples below use the same input path, `# Guide > ## Setup > ### Flags`, with
`deepest-levels: 2`.

| Mode | Text produced | What it isolates |
|---|---|---|
| `full` (default) | `# Guide > ## Setup > ### Flags\n\n` + piece | today's behavior, the baseline |
| `deepest` | `## Setup > ### Flags\n\n` + piece | is the full ancestry dead weight? |
| `plain` | `Guide > Setup > Flags\n\n` + piece | are the `#` marks noise? |
| `none` | piece only | what is the breadcrumb worth at all? |
| `embed-only` | `full` embedded, stripped from stored text | does it help the vector or the keyword side? |

`deepest` and `plain` are independent axes that happen to both shrink the header; the eval measures
them separately rather than combining them, so a win can be attributed.

`deepest` keeps the deepest levels because the specific heading carries the signal and the root
title repeats across the whole document.

## Architecture

Composition stays in `MarkdownChunker` for four of the five modes. That is deliberate: it keeps the
breadcrumb inside `chunk.text()` before `capToBudget` runs (`IngestService.java:195`), so the
2000-char embed budget continues to account for it exactly as it does today. No budget arithmetic
changes, and `capToBudget` keeps the idempotence the record path depends on
(`RecordIngestService.java:130` calls it, then `ingestChunks` calls it again).

`embed-only` is the one mode that needs `IngestService`, because it is the only one where embedded
text and stored text differ. It is implemented by *removing*, never adding: the chunker composes
with `full`, and `IngestService` strips the known prefix before storing. Storage only ever shrinks,
so it cannot overflow any budget.

```
MarkdownChunker(style)                    IngestService
  piece + rendered breadcrumb  ---->  capToBudget  ---->  embed(chunk.text())
                                                    ---->  store(style == EMBED_ONLY
                                                                 ? stripPrefix(chunk.text())
                                                                 : chunk.text())
```

Stripping is deterministic, not parsing: the chunk carries `headingPath`, so the prefix to remove is
exactly `render(FULL, headingPath, deepestLevels) + "\n\n"`, verified with `startsWith` before
removal.

## Components

```
chunk/HeadingStyle.java          enum FULL, DEEPEST, PLAIN, NONE, EMBED_ONLY + pure render()
config/ChunkProperties.java      @ConfigurationProperties("app.chunk")
config/ChunkConfig.java          @Configuration @EnableConfigurationProperties(ChunkProperties.class)
```

```java
public static String render(HeadingStyle style, String headingPath, int deepestLevels)
```

Returns the breadcrumb text with no trailing separator, or `""` when there is nothing to render.
Callers add `"\n\n"`. `NONE` and a null `headingPath` both yield `""`. `EMBED_ONLY` renders
identically to `FULL`. `deepestLevels` is read only by `DEEPEST` and ignored by every other mode -
it is a parameter rather than a field because the function must stay pure and Spring-free.

`ChunkProperties` follows `RouteProperties.java`: plain getters and setters, one javadoc line per
field saying why the knob exists.

## Changed code

**`MarkdownChunker`** - two new constructor parameters, `HeadingStyle style` and `int
deepestLevels`, with the existing two-arg constructor retained as an overload delegating to
`(maxWords, fallback, HeadingStyle.FULL, 2)` so existing call sites and tests keep compiling and
passing unchanged. Line 113-116 becomes:

```java
String headingPath = breadcrumb.isEmpty() ? null : breadcrumb;   // unchanged: always the full path
String rendered = HeadingStyle.render(style, headingPath, deepestLevels);
for (String piece : pieces) {
    String text = rendered.isEmpty() ? piece : rendered + "\n\n" + piece;
    out.add(new Chunk(text, headingPath, position[0]++));
}
```

`headingPath` keeps carrying the **full** breadcrumb in every mode. It is the eval's matching key
(`RetrievalEvalTest.java:108`) and a search filter; changing it per mode would make the eval rows
incomparable.

**`IngestService`** - `markdown` stops being an inline field initialiser (`IngestService.java:49`)
and is built in the constructor from `ChunkProperties`, so the eval can vary it. At the storage
call, `chunk.text()` becomes `storeText(chunk)`, which strips for `EMBED_ONLY` and returns the text
unchanged otherwise. The Qdrant upsert takes the same `storeText`.

## Configuration

```yaml
app:
  chunk:
    heading-style: full        # full | deepest | plain | none | embed-only
    deepest-levels: 2          # only used by `deepest`
```

Default `full` reproduces today's behavior byte for byte. Merging this must not move any existing
number until a mode is chosen deliberately on eval evidence.

## Operational constraint

Changing `heading-style` changes the text that was embedded, so it **invalidates every existing
vector** and requires a full re-ingest. For `embed-only` it also changes stored `content`, and
therefore `tsv`. This is a deploy-time knob, not a live toggle.

## Failure modes

`HeadingStyle.render` is pure - no I/O, no model call. Degenerate inputs return less text, never an
exception.

| Input | Behavior |
|---|---|
| `headingPath` null (content before any heading, `MarkdownChunkerTest:128`) | `""` |
| `headingPath` shallower than `deepest-levels` | whole path, unchanged |
| `deepest-levels` <= 0 | treated as 1 |
| a heading containing a literal `>` | split on `" > "` with spaces, matching how `breadcrumb()` joins (`MarkdownChunker.java:121`) |
| `EMBED_ONLY` and stored text does not start with the expected prefix | store unchanged, do not throw - stripping is an optimisation, not a correctness requirement |
| invalid `heading-style` in yaml | Spring binding fails at startup, before any bad vector is written |

The raw-text path (`WordWindowChunker`) and the record path have no headings, so every mode is a
no-op for them. Record ingest is untouched.

## Tests

1. **`HeadingStyleTest`** - pure unit, no Spring. One case per mode against
   `# Guide > ## Setup > ### Detail`, plus every row of the failure-mode table.

2. **`MarkdownChunkerTest` additions** - one test per mode asserting the emitted `chunk.text()`, and
   an assertion that `chunk.headingPath()` is the full path in all five modes. The existing tests
   stay untouched and must keep passing: that is the proof the default is unchanged.

3. **Storage divergence** - integration test with the fake embedding provider pattern
   (`DocumentIntegrationTest.java:58`). Under `embed-only`, ingest a document and assert both halves:

   ```java
   assertThat(fakeEmbedder.lastInput()).startsWith("# Guide");     // breadcrumb DID reach the embedder
   assertThat(jdbc.queryForObject(
       "select content from chunks where doc_id = ?", String.class, "d"))
       .doesNotContain("# Guide");                                  // and did NOT reach storage
   ```

   Plus the mirror case: under `full`, stored content *does* contain the breadcrumb.

4. **`HeadingStyleEvalTest`** - new `@Tag("eval-heading")` class, added to `<excludedGroups>` in
   `pom.xml:21` so the normal build skips it. For each of the five modes: rebuild `IngestService`'s
   chunker, re-ingest the corpus, run all six backends. Prints a 5x6 table of recall@5 / MRR /
   hit@1.

   Cost warning: five full corpus ingests against real Ollama. Ollama timing swings hard under
   memory pressure, so reap stray JVMs and containers before trusting the numbers.

## Reading the result

```
style        fts    pgvector  qdrant  hybrid  rerank  graph
full         ...      ...      ...     ...     ...     ...   <- today
deepest      ...      ...
plain        ...      ...
none         ...      ...
embed-only   ...      ...
```

- **`none` is the honest baseline.** If it ties `full`, the breadcrumb has been buying nothing and
  the simplest code wins.
- **`fts` moves only for `none` and `embed-only`.** CORRECTION, added after the run: this rule was
  justified by "only those two change stored `content`", which is wrong - `deepest` and `plain`
  change stored content too. `fts` did hold steady across `full`/`deepest`/`plain` in the actual run,
  but for a weaker reason: `to_tsvector` discards `#` as punctuation, and dropping ancestor headings
  did not reorder these 18 questions. Treat a steady `fts` as weak corroboration, not as the
  containment proof. The containment proof is `HeadingStyleStorageIntegrationTest`, which asserts on
  the stored string directly.
- **`embed-only` vs `full` splits the question.** If `embed-only` wins, the breadcrumb helps the
  vector and hurts BM25 by double-counting. If `full` wins, the keyword side wants those words too.
- **`plain` vs `full` prices the `#` marks.** A win for `plain` is free - strictly less text.
- **A tie across all five rows is a real result.** Record it, delete the losing complexity, keep the
  simplest mode that ties, and move on to the LLM-written context experiment.

## Later

If the breadcrumb turns out to matter, the natural follow-ups are the document label (filename, not
in the embedded text today) and then LLM-written context sentences - one call per chunk at ingest,
roughly 508k input tokens for a single 10k-token document that splits 50 ways, made affordable by
prompt caching over the repeated document prefix. Same cost profile the repo already accepted once
for `app.graph.edges: semantic` (`application.yml:79`).
