# Knowledge Base (MD Import + Ask + UI + Eval) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the sandbox into a project knowledge base: upload markdown files, chunk them markdown-aware with metadata, ask questions answered by a local Ollama LLM from retrieved chunks, drive it from a simple static UI, and measure quality with a Java-native eval harness.

**Architecture:** Every new capability is a new class behind an interface (project invariant). `Chunker` becomes an interface with `WordWindowChunker` (existing logic, renamed) and `MarkdownChunker` (new, commonmark-java AST walk). Chunks gain `source_file` / `heading_path` metadata flowing through pg + Qdrant + `SearchHit`. `ChatProvider` mirrors the `EmbeddingProvider` pattern (Ollama now, Azure swap later). `AskService` = retrieve (hybrid + rerank) then generate. Eval = JUnit-tagged tests excluded from the normal build.

**Tech Stack:** Java 21, Spring Boot 3.5.6, JdbcTemplate, pgvector, Qdrant Java client, Ollama (embeddings + chat), commonmark-java (NEW dependency, approved 2026-07-02), Testcontainers, vanilla JS static UI.

**Spec:** `docs/superpowers/specs/2026-07-02-knowledge-base-design.md`

## Global Constraints

- Build with `./mvnw`, never bare `mvn`.
- NEVER run `git add` or `git commit` - the user commits manually. Each task ends at a checkpoint: report the task done and STOP for the user's commit.
- No Lombok. No new dependencies beyond `org.commonmark:commonmark` (approved). SnakeYAML is already on the classpath via Spring Boot - use it for the golden set, do not add a YAML library.
- Keep the existing surefire `api.version=1.44` system property when editing the surefire plugin block (Docker 29 fix).
- Record every off-spec decision or deviation in `docs/implementation-notes.md` as you go (user rule).
- Code comments in English. Never use the em-dash character in code or docs; use "-".
- Known spec deviation (record in implementation-notes when implementing Task 1): spec sketched `chunk(String docId, String text)`; the plan drops the unused `docId` parameter - breadcrumbs come from headings inside the text, docId is a storage concern.

## Prerequisites (verify before starting)

- Docker running (Testcontainers).
- Ollama running at `localhost:11434` with `nomic-embed-text` pulled.
- A chat-capable model pulled. Task 5 step 1 checks with `ollama list` and sets the default property accordingly.

---

### Task 1: Chunk model + Chunker interface + WordWindowChunker rename

**Files:**
- Create: `src/main/java/com/example/springbootrag/chunk/Chunk.java`
- Create: `src/main/java/com/example/springbootrag/chunk/WordWindowChunker.java` (logic moved from `Chunker.java`)
- Modify: `src/main/java/com/example/springbootrag/chunk/Chunker.java` (class becomes interface)
- Modify: `src/main/java/com/example/springbootrag/service/IngestService.java` (compile fix only)
- Test: rename `src/test/java/com/example/springbootrag/chunk/ChunkerTest.java` to `WordWindowChunkerTest.java`

**Interfaces:**
- Produces: `record Chunk(String text, String headingPath, int position)`; `interface Chunker { List<Chunk> chunk(String text); }`; `class WordWindowChunker implements Chunker` with constructor `(int windowWords, int overlapWords)`. Tasks 2, 3, 4 build on these exact types.

- [ ] **Step 1: Write the failing test (rename + adapt)**

Rename `ChunkerTest.java` to `WordWindowChunkerTest.java` with content:

```java
package com.example.springbootrag.chunk;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class WordWindowChunkerTest {

    @Test
    void splitsByWordCountWithOverlap() {
        WordWindowChunker chunker = new WordWindowChunker(5, 2); // 5 words/chunk, 2 overlap
        String text = "one two three four five six seven eight";
        List<Chunk> chunks = chunker.chunk(text);

        assertThat(chunks).extracting(Chunk::text).containsExactly(
                "one two three four five",
                "four five six seven eight"
        );
        assertThat(chunks).extracting(Chunk::position).containsExactly(0, 1);
        assertThat(chunks).extracting(Chunk::headingPath).containsOnlyNulls();
    }

    @Test
    void shortTextIsOneChunk() {
        WordWindowChunker chunker = new WordWindowChunker(5, 2);
        assertThat(chunker.chunk("only three words"))
                .extracting(Chunk::text).containsExactly("only three words");
    }

    @Test
    void blankTextYieldsNoChunks() {
        WordWindowChunker chunker = new WordWindowChunker(5, 2);
        assertThat(chunker.chunk("   ")).isEmpty();
    }

    @Test
    void overlapMustBeSmallerThanWindow() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new WordWindowChunker(5, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=WordWindowChunkerTest`
Expected: COMPILE ERROR (no `Chunk`, no `WordWindowChunker`).

- [ ] **Step 3: Implement**

`src/main/java/com/example/springbootrag/chunk/Chunk.java`:

```java
package com.example.springbootrag.chunk;

/** One ingestible chunk: text to index, optional heading breadcrumb, position within the doc. */
public record Chunk(String text, String headingPath, int position) {}
```

Replace the whole content of `Chunker.java`:

```java
package com.example.springbootrag.chunk;

import java.util.List;

/** Splits document text into ingestible chunks. */
public interface Chunker {
    List<Chunk> chunk(String text);
}
```

`src/main/java/com/example/springbootrag/chunk/WordWindowChunker.java` (moved logic, adapted to `Chunk`):

```java
package com.example.springbootrag.chunk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Splits text into overlapping word windows. Structure-blind fallback strategy. */
public class WordWindowChunker implements Chunker {

    private final int windowWords;
    private final int overlapWords;

    public WordWindowChunker(int windowWords, int overlapWords) {
        if (overlapWords >= windowWords) {
            throw new IllegalArgumentException("overlap must be smaller than window");
        }
        this.windowWords = windowWords;
        this.overlapWords = overlapWords;
    }

    @Override
    public List<Chunk> chunk(String text) {
        List<Chunk> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        String[] words = text.trim().split("\\s+");
        int step = windowWords - overlapWords;
        int position = 0;
        for (int start = 0; start < words.length; start += step) {
            int end = Math.min(start + windowWords, words.length);
            chunks.add(new Chunk(String.join(" ", Arrays.copyOfRange(words, start, end)), null, position++));
            if (end == words.length) {
                break;
            }
        }
        return chunks;
    }
}
```

In `IngestService.java` change the chunker field and loop (keep everything else):

```java
private final Chunker chunker = new WordWindowChunker(120, 20);
```

and in `ingest(...)`:

```java
List<Chunk> chunks = chunker.chunk(text);
int stored = 0;
for (Chunk chunk : chunks) {
    float[] vec = embeddings.embed(chunk.text());
    long id = pgVector.insert(docId, chunk.position(), chunk.text(), vec);
    try {
        qdrant.upsert(id, docId, chunk.position(), chunk.text(), vec);
    } catch (ExecutionException | InterruptedException e) {
        throw new IllegalStateException("Qdrant upsert failed", e);
    }
    stored++;
}
return stored;
```

Add import `com.example.springbootrag.chunk.Chunk` and `WordWindowChunker`.

- [ ] **Step 4: Run full test suite**

Run: `./mvnw -q test`
Expected: ALL PASS (integration tests need Docker).

- [ ] **Step 5: Checkpoint - report task done to user. Do NOT run git commands; user commits manually.**

---

### Task 2: MarkdownChunker

**Files:**
- Modify: `pom.xml` (add commonmark)
- Create: `src/main/java/com/example/springbootrag/chunk/MarkdownChunker.java`
- Test: `src/test/java/com/example/springbootrag/chunk/MarkdownChunkerTest.java`

**Interfaces:**
- Consumes: `Chunk`, `Chunker`, `WordWindowChunker` from Task 1.
- Produces: `class MarkdownChunker implements Chunker` with constructor `(int maxWords, WordWindowChunker fallback)`. Task 4 instantiates `new MarkdownChunker(300, new WordWindowChunker(120, 20))`.

Behavior contract:
- Split on heading boundaries (any level).
- Every chunk text is prefixed with its breadcrumb line, e.g. `# Title > ## Setup`, followed by a blank line. `headingPath` field = the same breadcrumb (null when content precedes any heading).
- Fenced/indented code blocks and pipe tables are atomic - never split, even if over `maxWords` (they exceed the cap rather than break).
- A section longer than `maxWords` is packed block-by-block into multiple chunks; a single over-cap prose block falls back to the word-window strategy.
- Headings with no content produce no chunk.

- [ ] **Step 1: Add the commonmark dependency**

In `pom.xml` `<properties>` add:

```xml
<commonmark.version>0.24.0</commonmark.version>
```

(Check Maven Central for the latest `org.commonmark:commonmark`; use it if newer than 0.24.0.)

In `<dependencies>` (after the springdoc entry, before the DJL block):

```xml
<!-- commonmark-java: markdown AST for the structure-aware chunker (zero transitive deps) -->
<dependency>
    <groupId>org.commonmark</groupId>
    <artifactId>commonmark</artifactId>
    <version>${commonmark.version}</version>
</dependency>
```

Run: `./mvnw -q compile`
Expected: BUILD SUCCESS (dependency resolves).

- [ ] **Step 2: Write the failing tests**

`src/test/java/com/example/springbootrag/chunk/MarkdownChunkerTest.java`:

```java
package com.example.springbootrag.chunk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownChunkerTest {

    private final MarkdownChunker chunker =
            new MarkdownChunker(30, new WordWindowChunker(20, 5));

    @Test
    void splitsOnHeadingsWithBreadcrumb() {
        String md = """
                # Guide

                Intro paragraph here.

                ## Setup

                Install the tool first.

                ## Usage

                Run the command after setup.
                """;
        List<Chunk> chunks = chunker.chunk(md);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).headingPath()).isEqualTo("# Guide");
        assertThat(chunks.get(0).text()).startsWith("# Guide\n\n").contains("Intro paragraph here.");
        assertThat(chunks.get(1).headingPath()).isEqualTo("# Guide > ## Setup");
        assertThat(chunks.get(1).text()).contains("Install the tool first.");
        assertThat(chunks.get(2).headingPath()).isEqualTo("# Guide > ## Usage");
        assertThat(chunks).extracting(Chunk::position).containsExactly(0, 1, 2);
    }

    @Test
    void headingStackPopsOnSiblingAndParent() {
        String md = """
                # Doc

                ## A

                content a

                ### A1

                content a1

                ## B

                content b
                """;
        List<Chunk> chunks = chunker.chunk(md);

        assertThat(chunks).extracting(Chunk::headingPath).containsExactly(
                "# Doc > ## A",
                "# Doc > ## A > ### A1",
                "# Doc > ## B"
        );
    }

    @Test
    void codeBlockStaysAtomicEvenWhenOverCap() {
        // 40+ words of code, cap is 30: must stay one piece, never word-window split
        StringBuilder code = new StringBuilder("```java\n");
        for (int i = 0; i < 45; i++) code.append("var v").append(i).append(" = ").append(i).append(";\n");
        code.append("```");
        String md = "# Code\n\n" + code;

        List<Chunk> chunks = chunker.chunk(md);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).contains("```java").contains("var v44 = 44;");
    }

    @Test
    void pipeTableStaysAtomic() {
        String md = """
                # T

                | col1 | col2 | col3 | col4 | col5 | col6 | col7 | col8 |
                |------|------|------|------|------|------|------|------|
                | a1   | a2   | a3   | a4   | a5   | a6   | a7   | a8   |
                | b1   | b2   | b3   | b4   | b5   | b6   | b7   | b8   |
                | c1   | c2   | c3   | c4   | c5   | c6   | c7   | c8   |
                """;
        List<Chunk> chunks = chunker.chunk(md);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).contains("| a1").contains("| c8   |");
    }

    @Test
    void oversizedProseSectionSplitsIntoMultipleChunksWithBreadcrumbOnEach() {
        StringBuilder para = new StringBuilder();
        for (int i = 0; i < 50; i++) para.append("word").append(i).append(" ");
        String md = "# Big\n\n" + para.toString().trim();

        List<Chunk> chunks = chunker.chunk(md); // 50 words, cap 30 -> word-window fallback

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.text()).startsWith("# Big\n\n");
            assertThat(c.headingPath()).isEqualTo("# Big");
        });
    }

    @Test
    void twoSmallParagraphsPackIntoOneChunk() {
        String md = """
                # P

                first short paragraph.

                second short paragraph.
                """;
        List<Chunk> chunks = chunker.chunk(md);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).contains("first short").contains("second short");
    }

    @Test
    void contentBeforeAnyHeadingHasNullHeadingPath() {
        String md = "no headings at all, plain prose.";
        List<Chunk> chunks = chunker.chunk(md);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).headingPath()).isNull();
        assertThat(chunks.get(0).text()).isEqualTo("no headings at all, plain prose.");
    }

    @Test
    void headingWithNoContentProducesNoChunk() {
        String md = """
                # Empty

                ## AlsoEmpty

                ## HasContent

                real text
                """;
        List<Chunk> chunks = chunker.chunk(md);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).headingPath()).isEqualTo("# Empty > ## HasContent");
    }

    @Test
    void blankInputYieldsNoChunks() {
        assertThat(chunker.chunk("   ")).isEmpty();
        assertThat(chunker.chunk(null)).isEmpty();
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./mvnw -q test -Dtest=MarkdownChunkerTest`
Expected: COMPILE ERROR (`MarkdownChunker` not defined).

- [ ] **Step 4: Implement MarkdownChunker**

`src/main/java/com/example/springbootrag/chunk/MarkdownChunker.java`:

```java
package com.example.springbootrag.chunk;

import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.StringJoiner;

/**
 * Markdown-aware chunker: splits on heading boundaries, keeps code blocks and
 * pipe tables atomic, and prefixes every chunk with its heading breadcrumb
 * (e.g. "# Title > ## Setup") so retrieval keeps section context.
 * Sections over maxWords are packed block-by-block; a single over-cap prose
 * block falls back to the word-window strategy. Atomic blocks may exceed the cap.
 */
public class MarkdownChunker implements Chunker {

    private final int maxWords;
    private final WordWindowChunker fallback;
    private final Parser parser = Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS)
            .build();
    private final TextContentRenderer headingRenderer = TextContentRenderer.builder().build();

    public MarkdownChunker(int maxWords, WordWindowChunker fallback) {
        this.maxWords = maxWords;
        this.fallback = fallback;
    }

    private record Block(String text, int words, boolean atomic) {}
    private record Crumb(int level, String text) {}

    @Override
    public List<Chunk> chunk(String markdown) {
        List<Chunk> out = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return out;
        }
        Node doc = parser.parse(markdown);
        String[] lines = markdown.split("\n", -1);
        Deque<Crumb> crumbs = new ArrayDeque<>();
        List<Block> section = new ArrayList<>();
        int[] position = {0};

        for (Node node = doc.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof Heading heading) {
                flushSection(section, breadcrumb(crumbs), out, position);
                while (!crumbs.isEmpty() && crumbs.peekLast().level() >= heading.getLevel()) {
                    crumbs.removeLast();
                }
                String title = headingRenderer.render(heading).trim();
                crumbs.addLast(new Crumb(heading.getLevel(), "#".repeat(heading.getLevel()) + " " + title));
            } else {
                String text = sourceOf(node, lines);
                if (!text.isBlank()) {
                    boolean atomic = node instanceof FencedCodeBlock
                            || node instanceof IndentedCodeBlock
                            || text.stripLeading().startsWith("|");
                    section.add(new Block(text, countWords(text), atomic));
                }
            }
        }
        flushSection(section, breadcrumb(crumbs), out, position);
        return out;
    }

    /** Greedily packs a section's blocks into chunks of at most maxWords. */
    private void flushSection(List<Block> section, String breadcrumb, List<Chunk> out, int[] position) {
        if (section.isEmpty()) {
            return;
        }
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentWords = 0;

        for (Block block : section) {
            if (block.words() > maxWords && !block.atomic()) {
                // Over-cap prose: flush what we have, word-window split the block.
                if (currentWords > 0) {
                    pieces.add(current.toString());
                    current = new StringBuilder();
                    currentWords = 0;
                }
                for (Chunk piece : fallback.chunk(block.text())) {
                    pieces.add(piece.text());
                }
                continue;
            }
            if (currentWords > 0 && currentWords + block.words() > maxWords) {
                pieces.add(current.toString());
                current = new StringBuilder();
                currentWords = 0;
            }
            if (currentWords > 0) {
                current.append("\n\n");
            }
            current.append(block.text());
            currentWords += block.words();
        }
        if (currentWords > 0) {
            pieces.add(current.toString());
        }
        String headingPath = breadcrumb.isEmpty() ? null : breadcrumb;
        for (String piece : pieces) {
            String text = headingPath == null ? piece : headingPath + "\n\n" + piece;
            out.add(new Chunk(text, headingPath, position[0]++));
        }
        section.clear();
    }

    private static String breadcrumb(Deque<Crumb> crumbs) {
        StringJoiner joiner = new StringJoiner(" > ");
        for (Crumb crumb : crumbs) {
            joiner.add(crumb.text());
        }
        return joiner.toString();
    }

    /** Original markdown text of a block node, recovered via source spans. */
    private static String sourceOf(Node node, String[] lines) {
        var spans = node.getSourceSpans();
        if (spans.isEmpty()) {
            return "";
        }
        int first = spans.get(0).getLineIndex();
        int last = spans.get(spans.size() - 1).getLineIndex();
        return String.join("\n", Arrays.copyOfRange(lines, first, last + 1));
    }

    private static int countWords(String text) {
        return text.trim().split("\\s+").length;
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./mvnw -q test -Dtest=MarkdownChunkerTest`
Expected: ALL PASS. If a breadcrumb assertion fails, debug the heading stack; if an atomicity test fails, check the `atomic` detection and packing loop.

- [ ] **Step 6: Run full suite**

Run: `./mvnw -q test`
Expected: ALL PASS.

- [ ] **Step 7: Checkpoint - report task done to user. Do NOT run git commands; user commits manually.**

---

### Task 3: Metadata columns end-to-end (schema, SearchHit, repositories, IngestService)

**Files:**
- Modify: `src/main/resources/schema.sql`
- Modify: `src/main/java/com/example/springbootrag/model/SearchHit.java`
- Modify: `src/main/java/com/example/springbootrag/repository/PgVectorRepository.java`
- Modify: `src/main/java/com/example/springbootrag/repository/PgFtsRepository.java`
- Modify: `src/main/java/com/example/springbootrag/repository/QdrantRepository.java`
- Modify: `src/main/java/com/example/springbootrag/service/IngestService.java`
- Modify (mechanical `new SearchHit(` fixes): `src/test/java/com/example/springbootrag/fusion/RrfFusionTest.java`, `src/test/java/com/example/springbootrag/service/SearchServiceRerankTest.java`, `src/test/java/com/example/springbootrag/rerank/FakeReranker.java`, `src/test/java/com/example/springbootrag/rerank/IdentityRerankerTest.java`, `src/test/java/com/example/springbootrag/rerank/DjlRerankerManualTest.java` (whichever of these construct `SearchHit`)

**Interfaces:**
- Consumes: `Chunk` from Task 1.
- Produces: `record SearchHit(long id, String docId, int chunkIndex, String content, String sourceFile, String headingPath, double score)`;
  `PgVectorRepository.insert(String docId, int chunkIndex, String content, String sourceFile, String headingPath, float[] embedding)`;
  `PgVectorRepository.listDocuments()` returning `List<DocumentSummary>` where `record DocumentSummary(String docId, String sourceFile, int chunkCount)` (new file in `model`);
  `QdrantRepository.upsert(long id, String docId, int chunkIndex, String content, String sourceFile, String headingPath, float[] embedding)`;
  `IngestService.ingestChunks(String docId, String sourceFile, List<Chunk> chunks)` and `IngestService.ingestMarkdown(String docId, String sourceFile, String markdown)`.

- [ ] **Step 1: Extend schema.sql**

Add the two columns to the `CREATE TABLE` (for fresh databases) AND idempotent `ALTER`s (for the existing database; `spring.sql.init.mode=always` reruns this file at startup):

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS chunks (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    doc_id       VARCHAR(255) NOT NULL,
    chunk_index  INT NOT NULL,
    content      TEXT NOT NULL,
    source_file  VARCHAR(512),
    heading_path TEXT,
    tsv          tsvector GENERATED ALWAYS AS (to_tsvector('english', content)) STORED,
    embedding    vector(768) NOT NULL,
    created_at   TIMESTAMP DEFAULT now()
);

ALTER TABLE chunks ADD COLUMN IF NOT EXISTS source_file VARCHAR(512);
ALTER TABLE chunks ADD COLUMN IF NOT EXISTS heading_path TEXT;

CREATE INDEX IF NOT EXISTS idx_chunks_tsv ON chunks USING gin (tsv);
CREATE INDEX IF NOT EXISTS idx_chunks_embedding ON chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_chunks_doc_id ON chunks (doc_id);
```

- [ ] **Step 2: Extend SearchHit**

```java
package com.example.springbootrag.model;

/** One search result row, shared by every backend. Metadata fields are null for pre-metadata rows. */
public record SearchHit(
        long id,
        String docId,
        int chunkIndex,
        String content,
        String sourceFile,
        String headingPath,
        double score
) {}
```

Create `src/main/java/com/example/springbootrag/model/DocumentSummary.java`:

```java
package com.example.springbootrag.model;

/** One ingested document: id, original filename (null for raw-text ingest), chunk count. */
public record DocumentSummary(String docId, String sourceFile, int chunkCount) {}
```

- [ ] **Step 3: Update repositories**

`PgVectorRepository` - new insert signature, mapper, and `listDocuments`:

```java
/** Inserts one chunk and returns its generated id. */
public long insert(String docId, int chunkIndex, String content,
                   String sourceFile, String headingPath, float[] embedding) {
    return jdbc.queryForObject(
            "INSERT INTO chunks (doc_id, chunk_index, content, source_file, heading_path, embedding) " +
                    "VALUES (?, ?, ?, ?, ?, ?::vector) RETURNING id",
            Long.class,
            docId, chunkIndex, content, sourceFile, headingPath, toVectorLiteral(embedding));
}

/** Vector search: lower cosine distance = more similar, so we sort ascending and invert to a score. */
public List<SearchHit> search(float[] queryEmbedding, int topK) {
    return jdbc.query(
            "SELECT id, doc_id, chunk_index, content, source_file, heading_path, " +
                    "       embedding <=> ?::vector AS distance " +
                    "FROM chunks ORDER BY distance ASC LIMIT ?",
            (rs, rowNum) -> new SearchHit(
                    rs.getLong("id"),
                    rs.getString("doc_id"),
                    rs.getInt("chunk_index"),
                    rs.getString("content"),
                    rs.getString("source_file"),
                    rs.getString("heading_path"),
                    1.0 - rs.getDouble("distance")),
            toVectorLiteral(queryEmbedding), topK);
}

/** One row per ingested document, for the documents list endpoint. */
public List<com.example.springbootrag.model.DocumentSummary> listDocuments() {
    return jdbc.query(
            "SELECT doc_id, MAX(source_file) AS source_file, COUNT(*) AS chunk_count " +
                    "FROM chunks GROUP BY doc_id ORDER BY doc_id",
            (rs, rowNum) -> new com.example.springbootrag.model.DocumentSummary(
                    rs.getString("doc_id"),
                    rs.getString("source_file"),
                    rs.getInt("chunk_count")));
}
```

`PgFtsRepository.search` - add the two columns to SELECT and mapper (same pattern: `rs.getString("source_file")`, `rs.getString("heading_path")` inserted before the score argument).

`QdrantRepository`:

```java
public void upsert(long id, String docId, int chunkIndex, String content,
                   String sourceFile, String headingPath, float[] embedding)
        throws ExecutionException, InterruptedException {
    Map<String, Value> payload = new HashMap<>();
    payload.put("doc_id", value(docId));
    payload.put("chunk_index", value((long) chunkIndex));
    payload.put("content", value(content));
    if (sourceFile != null) {
        payload.put("source_file", value(sourceFile));
    }
    if (headingPath != null) {
        payload.put("heading_path", value(headingPath));
    }
    PointStruct point = PointStruct.newBuilder()
            .setId(id(id))
            .setVectors(vectors(embedding))
            .putAllPayload(payload)
            .build();
    client.upsertAsync(collection, List.of(point)).get();
}
```

(add `import java.util.HashMap;`) and in `search(...)` build hits with:

```java
hits.add(new SearchHit(
        p.getId().getNum(),
        payload.get("doc_id").getStringValue(),
        (int) payload.get("chunk_index").getIntegerValue(),
        payload.get("content").getStringValue(),
        payload.containsKey("source_file") ? payload.get("source_file").getStringValue() : null,
        payload.containsKey("heading_path") ? payload.get("heading_path").getStringValue() : null,
        p.getScore()));
```

- [ ] **Step 4: Restructure IngestService**

```java
package com.example.springbootrag.service;

import com.example.springbootrag.chunk.Chunk;
import com.example.springbootrag.chunk.MarkdownChunker;
import com.example.springbootrag.chunk.WordWindowChunker;
import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.repository.PgVectorRepository;
import com.example.springbootrag.repository.QdrantRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutionException;

@Service
public class IngestService {

    private final EmbeddingProvider embeddings;
    private final PgVectorRepository pgVector;
    private final QdrantRepository qdrant;
    private final WordWindowChunker wordWindow = new WordWindowChunker(120, 20);
    private final MarkdownChunker markdown = new MarkdownChunker(300, new WordWindowChunker(120, 20));

    public IngestService(EmbeddingProvider embeddings,
                         PgVectorRepository pgVector,
                         QdrantRepository qdrant) {
        this.embeddings = embeddings;
        this.pgVector = pgVector;
        this.qdrant = qdrant;
    }

    /** Raw-text ingest (existing JSON endpoint): word-window chunking, no metadata. */
    public int ingest(String docId, String text) {
        return ingestChunks(docId, null, wordWindow.chunk(text));
    }

    /** Markdown file ingest: structure-aware chunking with heading breadcrumbs. */
    public int ingestMarkdown(String docId, String sourceFile, String markdownText) {
        return ingestChunks(docId, sourceFile, markdown.chunk(markdownText));
    }

    /**
     * Upsert-by-doc: clear any existing chunks for this docId first so re-ingesting
     * the same document replaces it instead of silently accumulating duplicates.
     */
    public int ingestChunks(String docId, String sourceFile, List<Chunk> chunks) {
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("docId is required");
        }
        delete(docId);
        for (Chunk chunk : chunks) {
            float[] vec = embeddings.embed(chunk.text());
            long id = pgVector.insert(docId, chunk.position(), chunk.text(),
                    sourceFile, chunk.headingPath(), vec);
            try {
                qdrant.upsert(id, docId, chunk.position(), chunk.text(),
                        sourceFile, chunk.headingPath(), vec);
            } catch (ExecutionException | InterruptedException e) {
                throw new IllegalStateException("Qdrant upsert failed", e);
            }
        }
        return chunks.size();
    }

    public void delete(String docId) {
        pgVector.deleteByDocId(docId);
        try {
            qdrant.deleteByDocId(docId);
        } catch (ExecutionException | InterruptedException e) {
            throw new IllegalStateException("Qdrant delete failed", e);
        }
    }
}
```

- [ ] **Step 5: Fix test compilation mechanically**

Run: `./mvnw -q test-compile` and fix every `new SearchHit(` call site the compiler reports: insert `null, null` before the final score argument. Example:

Before: `new SearchHit(1, "doc1", 0, "text", 0.9)`
After: `new SearchHit(1, "doc1", 0, "text", null, null, 0.9)`

- [ ] **Step 6: Add a metadata round-trip integration test**

Append to `SearchIntegrationTest`:

```java
@Test
void markdownIngestCarriesMetadataThroughAllBackends() {
    ingestService.ingestMarkdown("kb-doc", "kb doc.md", """
            # Pressure Guide

            ## Diagnosis

            hydraulic seepage caused a pressure drop on line 3
            """);

    List<SearchHit> vec = searchService.search("pgvector", "machine lost pressure", 10);
    assertThat(vec.get(0).docId()).isEqualTo("kb-doc");
    assertThat(vec.get(0).sourceFile()).isEqualTo("kb doc.md");
    assertThat(vec.get(0).headingPath()).isEqualTo("# Pressure Guide > ## Diagnosis");

    List<SearchHit> qd = searchService.search("qdrant", "machine lost pressure", 10);
    assertThat(qd.get(0).headingPath()).isEqualTo("# Pressure Guide > ## Diagnosis");

    List<SearchHit> fts = searchService.search("fts", "seepage", 10);
    assertThat(fts.get(0).sourceFile()).isEqualTo("kb doc.md");
}
```

(add `import java.util.List;` if missing - it is already there.)

- [ ] **Step 7: Run full suite**

Run: `./mvnw -q test`
Expected: ALL PASS.

- [ ] **Step 8: Checkpoint - report task done to user. Do NOT run git commands; user commits manually.**

---

### Task 4: Document endpoints (upload / list / delete)

**Files:**
- Create: `src/main/java/com/example/springbootrag/web/DocumentController.java`
- Modify: `src/main/resources/application.yml` (multipart limits)
- Modify: `src/main/java/com/example/springbootrag/web/GlobalExceptionHandler.java` (upload-size handler)
- Create: `src/test/java/com/example/springbootrag/integration/DocumentIntegrationTest.java`

**Interfaces:**
- Consumes: `IngestService.ingestMarkdown(docId, sourceFile, markdown)`, `IngestService.delete(docId)`, `PgVectorRepository.listDocuments()`, `DocumentSummary`, `IngestResponse(docId, chunks)` (existing DTO).
- Produces: `POST /documents` (multipart, part name `file`) returning `IngestResponse`; `GET /documents` returning `List<DocumentSummary>`; `DELETE /documents/{docId}`. Task 7's UI calls these.

- [ ] **Step 1: Write the failing integration test**

```java
package com.example.springbootrag.integration;

import com.example.springbootrag.embedding.EmbeddingProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.qdrant.QdrantContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DocumentIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("ragdb").withUsername("rag").withPassword("rag");

    @Container
    static QdrantContainer qdrant =
            new QdrantContainer(DockerImageName.parse("qdrant/qdrant:v1.9.0"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.qdrant.host", qdrant::getHost);
        registry.add("app.qdrant.port", qdrant::getGrpcPort);
    }

    /** Constant fake embedding: this test exercises upload plumbing, not similarity. */
    @TestConfiguration
    static class FakeEmbeddingConfig {
        @Bean
        @Primary
        EmbeddingProvider fakeEmbeddingProvider() {
            return new EmbeddingProvider() {
                @Override public float[] embed(String text) {
                    float[] v = new float[768];
                    v[0] = 1f;
                    return v;
                }
                @Override public int dimension() { return 768; }
            };
        }
    }

    @Autowired MockMvc mvc;

    @Test
    void uploadListDeleteRoundTrip() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "My Notes.md", "text/markdown",
                "# Notes\n\nsome useful content here".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/documents").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.docId").value("My-Notes"))
                .andExpect(jsonPath("$.chunks").value(1));

        mvc.perform(get("/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].docId").value("My-Notes"))
                .andExpect(jsonPath("$[0].sourceFile").value("My Notes.md"))
                .andExpect(jsonPath("$[0].chunkCount").value(1));

        mvc.perform(delete("/documents/My-Notes"))
                .andExpect(status().isOk());

        mvc.perform(get("/documents"))
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void rejectsNonMarkdownFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "data.pdf", "application/pdf", new byte[]{1, 2, 3});

        mvc.perform(multipart("/documents").file(file))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsInvalidUtf8() throws Exception {
        // 0xC3 followed by 0x28 is malformed UTF-8
        MockMultipartFile file = new MockMultipartFile(
                "file", "bad.md", "text/markdown", new byte[]{(byte) 0xC3, 0x28});

        mvc.perform(multipart("/documents").file(file))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reuploadReplacesInsteadOfDuplicating() throws Exception {
        MockMultipartFile v1 = new MockMultipartFile(
                "file", "doc.md", "text/markdown",
                "# A\n\nfirst version".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile v2 = new MockMultipartFile(
                "file", "doc.md", "text/markdown",
                "# A\n\nsecond version".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/documents").file(v1)).andExpect(status().isOk());
        mvc.perform(multipart("/documents").file(v2)).andExpect(status().isOk());

        mvc.perform(get("/documents"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].chunkCount").value(1));
    }
}
```

Note: the existing `IngestResponse` DTO field for the count is named `chunks` if it is `record IngestResponse(String docId, int chunks)`. Check `src/main/java/com/example/springbootrag/web/dto/IngestResponse.java` and align the jsonPath assertions with the real field names before running.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=DocumentIntegrationTest`
Expected: 404s / failures (no `/documents` endpoints yet).

- [ ] **Step 3: Implement DocumentController**

```java
package com.example.springbootrag.web;

import com.example.springbootrag.model.DocumentSummary;
import com.example.springbootrag.repository.PgVectorRepository;
import com.example.springbootrag.service.IngestService;
import com.example.springbootrag.web.dto.IngestResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
public class DocumentController {

    private static final long MAX_BYTES = 2 * 1024 * 1024;

    private final IngestService ingestService;
    private final PgVectorRepository pgVector;

    public DocumentController(IngestService ingestService, PgVectorRepository pgVector) {
        this.ingestService = ingestService;
        this.pgVector = pgVector;
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestResponse upload(@RequestParam("file") MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".md")) {
            throw new IllegalArgumentException("only .md files are accepted");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("file too large (max 2 MB)");
        }
        String text = decodeUtf8(file);
        String docId = sanitizeDocId(name);
        int stored = ingestService.ingestMarkdown(docId, name, text);
        return new IngestResponse(docId, stored);
    }

    @GetMapping("/documents")
    public List<DocumentSummary> list() {
        return pgVector.listDocuments();
    }

    @DeleteMapping("/documents/{docId}")
    public void delete(@PathVariable String docId) {
        ingestService.delete(docId);
    }

    /** Strict UTF-8 decode: malformed bytes are a client error, not replacement chars. */
    private static String decodeUtf8(MultipartFile file) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(file.getBytes()))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("file is not valid UTF-8");
        } catch (IOException e) {
            throw new IllegalStateException("could not read upload", e);
        }
    }

    /** "My Notes.md" -> "My-Notes". Same name re-upload replaces the document. */
    private static String sanitizeDocId(String filename) {
        String base = filename.substring(0, filename.length() - ".md".length());
        return base.replaceAll("[^a-zA-Z0-9._-]", "-");
    }
}
```

Match the `IngestResponse` constructor to the existing DTO (check its field order/names).

- [ ] **Step 4: Multipart limits + upload-size handler**

`application.yml` - under the existing `spring:` key add:

```yaml
  servlet:
    multipart:
      max-file-size: 2MB
      max-request-size: 3MB
```

`GlobalExceptionHandler` - add:

```java
@ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
public ProblemDetail handleTooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "file too large (max 2 MB)");
}
```

- [ ] **Step 5: Run tests**

Run: `./mvnw -q test -Dtest=DocumentIntegrationTest`
Expected: ALL PASS.

Run: `./mvnw -q test`
Expected: ALL PASS.

- [ ] **Step 6: Checkpoint - report task done to user. Do NOT run git commands; user commits manually.**

---

### Task 5: ChatProvider + OllamaChatProvider + config

**Files:**
- Create: `src/main/java/com/example/springbootrag/chat/ChatProvider.java`
- Create: `src/main/java/com/example/springbootrag/chat/OllamaChatProvider.java`
- Create: `src/main/java/com/example/springbootrag/chat/ChatUnavailableException.java`
- Create: `src/main/java/com/example/springbootrag/config/ChatProperties.java`
- Create: `src/main/java/com/example/springbootrag/config/ChatConfig.java`
- Modify: `src/main/java/com/example/springbootrag/web/GlobalExceptionHandler.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/example/springbootrag/chat/OllamaChatProviderTest.java`

**Interfaces:**
- Produces: `interface ChatProvider { String chat(String systemPrompt, String userPrompt); }`; `class ChatUnavailableException extends RuntimeException`; `ChatProperties` (prefix `app.chat`, fields `model`, `contextChunks`). Task 6 consumes all three.

- [ ] **Step 1: Pick the default chat model**

Run: `ollama list`
Pick a chat-capable model the user already has (prefer `qwen2.5:*`, then `llama3.*`). Use it as the default in `ChatProperties.model` and `application.yml` below (plan text uses `qwen2.5:7b` as stand-in - substitute the real one). If none is present, tell the user to pull one (e.g. `ollama pull qwen2.5:7b`) and continue with that name.

- [ ] **Step 2: Write the failing test**

Mirror the MockWebServer style of `OllamaEmbeddingProviderTest` (read it first and reuse its setup idioms):

```java
package com.example.springbootrag.chat;

import com.example.springbootrag.config.ChatProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OllamaChatProviderTest {

    private MockWebServer server;
    private OllamaChatProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        ChatProperties props = new ChatProperties();
        props.setModel("test-model");
        provider = new OllamaChatProvider(
                RestClient.builder().baseUrl(server.url("/").toString()).build(), props);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void sendsSystemAndUserMessagesAndReturnsReply() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"message": {"role": "assistant", "content": "the answer [1]"}}
                        """));

        String reply = provider.chat("you are helpful", "what is up?");

        assertThat(reply).isEqualTo("the answer [1]");
        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/chat");
        String body = req.getBody().readUtf8();
        assertThat(body).contains("\"model\":\"test-model\"");
        assertThat(body).contains("\"stream\":false");
        assertThat(body).contains("you are helpful");
        assertThat(body).contains("what is up?");
    }

    @Test
    void missingMessageBecomesChatUnavailable() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{}"));

        assertThatThrownBy(() -> provider.chat("s", "u"))
                .isInstanceOf(ChatUnavailableException.class);
    }

    @Test
    void connectionFailureBecomesChatUnavailable() throws Exception {
        server.shutdown(); // nothing listening anymore

        assertThatThrownBy(() -> provider.chat("s", "u"))
                .isInstanceOf(ChatUnavailableException.class);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=OllamaChatProviderTest`
Expected: COMPILE ERROR.

- [ ] **Step 4: Implement**

`ChatUnavailableException.java`:

```java
package com.example.springbootrag.chat;

/** Local chat model unreachable or returned garbage. Maps to HTTP 503; search paths stay usable. */
public class ChatUnavailableException extends RuntimeException {
    public ChatUnavailableException(String message) {
        super(message);
    }
    public ChatUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`ChatProvider.java`:

```java
package com.example.springbootrag.chat;

/** Generates one assistant reply from a system + user prompt pair. Ollama now, Azure swap later. */
public interface ChatProvider {
    String chat(String systemPrompt, String userPrompt);
}
```

`OllamaChatProvider.java`:

```java
package com.example.springbootrag.chat;

import com.example.springbootrag.config.ChatProperties;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

public class OllamaChatProvider implements ChatProvider {

    private final RestClient client;
    private final ChatProperties props;

    public OllamaChatProvider(RestClient client, ChatProperties props) {
        this.client = client;
        this.props = props;
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        ChatResponse resp;
        try {
            resp = client.post()
                    .uri("/api/chat")
                    .body(Map.of(
                            "model", props.getModel(),
                            "stream", false,
                            "messages", List.of(
                                    Map.of("role", "system", "content", systemPrompt),
                                    Map.of("role", "user", "content", userPrompt))))
                    .retrieve()
                    .body(ChatResponse.class);
        } catch (ResourceAccessException e) {
            throw new ChatUnavailableException("chat model unavailable: " + e.getMessage(), e);
        }
        if (resp == null || resp.message() == null || resp.message().content() == null) {
            throw new ChatUnavailableException("Ollama returned no chat message");
        }
        return resp.message().content();
    }

    private record ChatResponse(Message message) {}
    private record Message(String role, String content) {}
}
```

`ChatProperties.java`:

```java
package com.example.springbootrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.chat")
public class ChatProperties {
    /** Ollama chat model name. Substitute the model chosen in Task 5 step 1. */
    private String model = "qwen2.5:7b";
    /** How many retrieved chunks go into the ask prompt. */
    private int contextChunks = 5;

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getContextChunks() { return contextChunks; }
    public void setContextChunks(int contextChunks) { this.contextChunks = contextChunks; }
}
```

`ChatConfig.java` (mirror `EmbeddingConfig`; also mirror how `RerankProperties` gets registered - check `RerankConfig` for `@EnableConfigurationProperties` usage):

```java
package com.example.springbootrag.config;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.chat.OllamaChatProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(ChatProperties.class)
public class ChatConfig {

    @Bean
    public ChatProvider chatProvider(ChatProperties props,
                                     @Value("${app.ollama.base-url}") String baseUrl) {
        RestClient client = RestClient.builder().baseUrl(baseUrl).build();
        return new OllamaChatProvider(client, props);
    }
}
```

`GlobalExceptionHandler` - add:

```java
@ExceptionHandler(com.example.springbootrag.chat.ChatUnavailableException.class)
public ProblemDetail handleChatUnavailable(com.example.springbootrag.chat.ChatUnavailableException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
}
```

`application.yml` - add under `app:`:

```yaml
  chat:
    model: qwen2.5:7b     # substitute the model chosen in Task 5 step 1
    context-chunks: 5
```

- [ ] **Step 5: Run tests**

Run: `./mvnw -q test -Dtest=OllamaChatProviderTest`
Expected: ALL PASS.

- [ ] **Step 6: Checkpoint - report task done to user. Do NOT run git commands; user commits manually.**

---

### Task 6: AskService + GET /ask

**Files:**
- Create: `src/main/java/com/example/springbootrag/service/AskService.java`
- Create: `src/main/java/com/example/springbootrag/web/AskController.java`
- Create: `src/main/java/com/example/springbootrag/web/dto/AskResponse.java`
- Test: `src/test/java/com/example/springbootrag/service/AskServiceTest.java`

**Interfaces:**
- Consumes: `SearchService.search(String type, String query, int topK)`, `ChatProvider`, `ChatProperties`, `SearchHit` (7-field version from Task 3).
- Produces: `record AskResponse(String answer, List<Source> sources)` with nested `record Source(int index, String docId, String headingPath, double score, String content)`; `GET /ask?q=...` returning it. Task 7's UI and Task 9's judge consume `AskService.ask(String)`.

- [ ] **Step 1: Write the failing tests**

```java
package com.example.springbootrag.service;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.config.ChatProperties;
import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.web.dto.AskResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class AskServiceTest {

    private final SearchService searchService = mock(SearchService.class);

    /** Captures prompts and returns a canned answer. */
    static class FakeChat implements ChatProvider {
        String lastSystem;
        String lastUser;
        @Override public String chat(String systemPrompt, String userPrompt) {
            this.lastSystem = systemPrompt;
            this.lastUser = userPrompt;
            return "canned answer [1]";
        }
    }

    private final FakeChat chat = new FakeChat();
    private final ChatProperties props = new ChatProperties();
    private final AskService askService = new AskService(searchService, chat, props);

    @Test
    void buildsNumberedContextAndReturnsSources() {
        when(searchService.search(eq("rerank"), anyString(), anyInt())).thenReturn(List.of(
                new SearchHit(1, "doc-a", 0, "chunk one text", "a.md", "# A > ## S", 0.9),
                new SearchHit(2, "doc-b", 3, "chunk two text", "b.md", null, 0.7)));

        AskResponse resp = askService.ask("what happened?");

        assertThat(resp.answer()).isEqualTo("canned answer [1]");
        assertThat(chat.lastUser).contains("[1]").contains("chunk one text");
        assertThat(chat.lastUser).contains("[2]").contains("chunk two text");
        assertThat(chat.lastUser).contains("# A > ## S");
        assertThat(chat.lastUser).endsWith("Question: what happened?");
        assertThat(chat.lastSystem).contains("ONLY");

        assertThat(resp.sources()).hasSize(2);
        assertThat(resp.sources().get(0).index()).isEqualTo(1);
        assertThat(resp.sources().get(0).docId()).isEqualTo("doc-a");
        assertThat(resp.sources().get(0).headingPath()).isEqualTo("# A > ## S");
        assertThat(resp.sources().get(1).index()).isEqualTo(2);
    }

    @Test
    void emptyRetrievalShortCircuitsWithoutCallingLlm(){
        when(searchService.search(eq("rerank"), anyString(), anyInt())).thenReturn(List.of());
        ChatProvider mockChat = mock(ChatProvider.class);
        AskService svc = new AskService(searchService, mockChat, props);

        AskResponse resp = svc.ask("anything?");

        assertThat(resp.answer()).contains("empty");
        assertThat(resp.sources()).isEmpty();
        verifyNoInteractions(mockChat);
    }

    @Test
    void blankQuestionIsRejected() {
        assertThatThrownBy(() -> askService.ask("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -q test -Dtest=AskServiceTest`
Expected: COMPILE ERROR.

- [ ] **Step 3: Implement**

`AskResponse.java`:

```java
package com.example.springbootrag.web.dto;

import java.util.List;

/** RAG answer plus the chunks it was generated from. */
public record AskResponse(String answer, List<Source> sources) {

    public record Source(int index, String docId, String headingPath, double score, String content) {}
}
```

`AskService.java`:

```java
package com.example.springbootrag.service;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.config.ChatProperties;
import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.web.dto.AskResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Full RAG loop: retrieve (hybrid + rerank) then generate an answer from the chunks. */
@Service
public class AskService {

    static final String SYSTEM_PROMPT = """
            You are a knowledge-base assistant. Answer the question using ONLY the numbered \
            context chunks provided. Cite the chunks you used with their numbers in square \
            brackets, like [1] or [2]. If the context does not contain the answer, reply \
            exactly: Not found in knowledge base.""";

    private final SearchService searchService;
    private final ChatProvider chat;
    private final ChatProperties props;

    public AskService(SearchService searchService, ChatProvider chat, ChatProperties props) {
        this.searchService = searchService;
        this.chat = chat;
        this.props = props;
    }

    public AskResponse ask(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question is required");
        }
        // "rerank" = hybrid + reranker; with no reranker configured it degrades to plain hybrid.
        List<SearchHit> hits = searchService.search("rerank", question, props.getContextChunks());
        if (hits.isEmpty()) {
            return new AskResponse("Knowledge base is empty - no relevant chunks found.", List.of());
        }
        String answer = chat.chat(SYSTEM_PROMPT, buildUserPrompt(question, hits));
        List<AskResponse.Source> sources = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            sources.add(new AskResponse.Source(i + 1, h.docId(), h.headingPath(), h.score(), h.content()));
        }
        return new AskResponse(answer, sources);
    }

    private static String buildUserPrompt(String question, List<SearchHit> hits) {
        StringBuilder sb = new StringBuilder("Context:\n");
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            sb.append('[').append(i + 1).append("] (").append(h.docId());
            if (h.headingPath() != null) {
                sb.append(" - ").append(h.headingPath());
            }
            sb.append(")\n").append(h.content()).append("\n\n");
        }
        sb.append("Question: ").append(question);
        return sb.toString();
    }
}
```

`AskController.java`:

```java
package com.example.springbootrag.web;

import com.example.springbootrag.service.AskService;
import com.example.springbootrag.web.dto.AskResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AskController {

    private final AskService askService;

    public AskController(AskService askService) {
        this.askService = askService;
    }

    @GetMapping("/ask")
    public AskResponse ask(@RequestParam String q) {
        return askService.ask(q);
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./mvnw -q test -Dtest=AskServiceTest`
Expected: ALL PASS.

Run: `./mvnw -q test`
Expected: ALL PASS.

- [ ] **Step 5: Manual smoke test (needs pg, Qdrant, Ollama running per README)**

Run the app, upload a doc, ask:

```
curl -F "file=@README.md" http://localhost:8080/documents
curl "http://localhost:8080/ask?q=How do I run the tests?"
```

Expected: JSON with an `answer` citing chunk numbers and a `sources` array. Also verify Ollama-down behavior: stop Ollama, expect HTTP 503.

- [ ] **Step 6: Checkpoint - report task done to user. Do NOT run git commands; user commits manually.**

---

### Task 7: Static UI

**Files:**
- Create: `src/main/resources/static/index.html`
- Create: `src/main/resources/static/app.js`
- Create: `src/main/resources/static/style.css`

**Interfaces:**
- Consumes: `POST /documents` (multipart `file`), `GET /documents`, `DELETE /documents/{docId}`, `GET /search?q=&type=&topK=`, `GET /ask?q=`.
- Produces: browser UI at `http://localhost:8080/` (Spring Boot serves `static/index.html` at the root automatically).

- [ ] **Step 1: index.html**

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>RAG Knowledge Base</title>
    <link rel="stylesheet" href="style.css">
</head>
<body>
<h1>RAG Knowledge Base</h1>

<section id="import-zone">
    <h2>Import</h2>
    <div id="drop-area">
        <p>Drop a .md file here or</p>
        <input type="file" id="file-input" accept=".md">
    </div>
    <p id="import-status" class="status"></p>
    <table id="doc-table">
        <thead><tr><th>Doc ID</th><th>Source file</th><th>Chunks</th><th></th></tr></thead>
        <tbody></tbody>
    </table>
</section>

<section id="search-zone">
    <h2>Search</h2>
    <form id="search-form">
        <input type="text" id="search-q" placeholder="query..." required>
        <select id="search-type">
            <option value="hybrid" selected>hybrid</option>
            <option value="rerank">rerank</option>
            <option value="fts">fts</option>
            <option value="pgvector">pgvector</option>
            <option value="qdrant">qdrant</option>
        </select>
        <button type="submit">Search</button>
    </form>
    <div id="search-results"></div>
</section>

<section id="ask-zone">
    <h2>Ask</h2>
    <form id="ask-form">
        <input type="text" id="ask-q" placeholder="ask the knowledge base..." required>
        <button type="submit">Ask</button>
    </form>
    <p id="ask-status" class="status"></p>
    <div id="ask-answer"></div>
    <div id="ask-sources"></div>
</section>

<script src="app.js"></script>
</body>
</html>
```

- [ ] **Step 2: app.js**

```javascript
// Minimal client for the knowledge-base API. No framework - three fetch-backed zones.

const $ = (sel) => document.querySelector(sel);

// ---------- Import ----------

async function refreshDocs() {
    const res = await fetch('/documents');
    const docs = await res.json();
    const tbody = $('#doc-table tbody');
    tbody.innerHTML = '';
    for (const d of docs) {
        const tr = document.createElement('tr');
        tr.innerHTML = `<td>${esc(d.docId)}</td><td>${esc(d.sourceFile ?? '-')}</td><td>${d.chunkCount}</td>`;
        const td = document.createElement('td');
        const btn = document.createElement('button');
        btn.textContent = 'delete';
        btn.onclick = async () => {
            await fetch(`/documents/${encodeURIComponent(d.docId)}`, { method: 'DELETE' });
            refreshDocs();
        };
        td.appendChild(btn);
        tr.appendChild(td);
        tbody.appendChild(tr);
    }
}

async function uploadFile(file) {
    const status = $('#import-status');
    status.textContent = `uploading ${file.name}...`;
    const form = new FormData();
    form.append('file', file);
    const res = await fetch('/documents', { method: 'POST', body: form });
    if (res.ok) {
        const body = await res.json();
        status.textContent = `imported ${file.name}: ${body.chunks ?? JSON.stringify(body)} chunks`;
        refreshDocs();
    } else {
        status.textContent = `error: ${(await res.json()).detail ?? res.status}`;
    }
}

$('#file-input').addEventListener('change', (e) => {
    if (e.target.files.length) uploadFile(e.target.files[0]);
});

const dropArea = $('#drop-area');
dropArea.addEventListener('dragover', (e) => { e.preventDefault(); dropArea.classList.add('drag'); });
dropArea.addEventListener('dragleave', () => dropArea.classList.remove('drag'));
dropArea.addEventListener('drop', (e) => {
    e.preventDefault();
    dropArea.classList.remove('drag');
    if (e.dataTransfer.files.length) uploadFile(e.dataTransfer.files[0]);
});

// ---------- Search ----------

$('#search-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const q = $('#search-q').value;
    const type = $('#search-type').value;
    const res = await fetch(`/search?q=${encodeURIComponent(q)}&type=${type}&topK=10`);
    const container = $('#search-results');
    if (!res.ok) {
        container.textContent = `error: ${(await res.json()).detail ?? res.status}`;
        return;
    }
    const hits = await res.json();
    container.innerHTML = hits.length ? '' : '<p>no results</p>';
    for (const h of hits) {
        const div = document.createElement('div');
        div.className = 'hit';
        div.innerHTML = `
            <div class="hit-meta">${esc(h.docId)}${h.headingPath ? ' - ' + esc(h.headingPath) : ''}
                <span class="score">${h.score.toFixed(4)}</span></div>
            <pre>${esc(h.content)}</pre>`;
        container.appendChild(div);
    }
});

// ---------- Ask ----------

$('#ask-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const q = $('#ask-q').value;
    const status = $('#ask-status');
    const answerEl = $('#ask-answer');
    const sourcesEl = $('#ask-sources');
    status.textContent = 'thinking...';
    answerEl.textContent = '';
    sourcesEl.innerHTML = '';
    const res = await fetch(`/ask?q=${encodeURIComponent(q)}`);
    if (!res.ok) {
        status.textContent = `error: ${(await res.json()).detail ?? res.status}`;
        return;
    }
    const body = await res.json();
    status.textContent = '';
    answerEl.textContent = body.answer;
    for (const s of body.sources) {
        const details = document.createElement('details');
        details.innerHTML = `
            <summary>[${s.index}] ${esc(s.docId)}${s.headingPath ? ' - ' + esc(s.headingPath) : ''}</summary>
            <pre>${esc(s.content)}</pre>`;
        sourcesEl.appendChild(details);
    }
});

function esc(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

refreshDocs();
```

- [ ] **Step 3: style.css**

```css
body { font-family: system-ui, sans-serif; max-width: 900px; margin: 2rem auto; padding: 0 1rem; color: #222; }
h1 { font-size: 1.5rem; }
section { margin-bottom: 2.5rem; border-top: 1px solid #ddd; padding-top: 1rem; }
#drop-area { border: 2px dashed #aaa; border-radius: 8px; padding: 1.5rem; text-align: center; }
#drop-area.drag { border-color: #06c; background: #eef6ff; }
.status { color: #06c; min-height: 1.2em; }
table { border-collapse: collapse; width: 100%; margin-top: 1rem; }
th, td { text-align: left; padding: 0.4rem 0.6rem; border-bottom: 1px solid #eee; }
form { display: flex; gap: 0.5rem; margin-bottom: 1rem; }
input[type="text"] { flex: 1; padding: 0.5rem; }
button { padding: 0.4rem 0.9rem; cursor: pointer; }
.hit { border: 1px solid #eee; border-radius: 6px; padding: 0.6rem; margin-bottom: 0.6rem; }
.hit-meta { font-size: 0.85rem; color: #555; margin-bottom: 0.3rem; }
.score { float: right; color: #999; }
pre { white-space: pre-wrap; word-break: break-word; background: #fafafa; padding: 0.5rem; border-radius: 4px; }
#ask-answer { border-left: 3px solid #06c; padding: 0.6rem 1rem; margin-bottom: 1rem; white-space: pre-wrap; }
details { margin-bottom: 0.4rem; }
summary { cursor: pointer; font-size: 0.9rem; color: #444; }
```

- [ ] **Step 4: Manual verification (needs full stack running)**

Start pg + Qdrant + Ollama + the app. Open `http://localhost:8080/`. Verify:
- Upload a real `.md` (drag-drop AND file picker) - chunk count appears, doc listed.
- Upload a `.txt` - error message shown, not a crash.
- Search with each backend type - results with heading paths render.
- Ask a question answerable from the doc - answer with `[n]` citations, sources expandable.
- Delete the doc - list empties; ask again - "Knowledge base is empty" answer.

- [ ] **Step 5: Checkpoint - report task done to user. Do NOT run git commands; user commits manually.**

---

### Task 8: Retrieval eval harness (golden set + metrics)

**Files:**
- Create: `src/test/resources/eval/golden.yaml`
- Create: `src/test/java/com/example/springbootrag/eval/GoldenEntry.java`
- Create: `src/test/java/com/example/springbootrag/eval/GoldenSet.java`
- Create: `src/test/java/com/example/springbootrag/eval/GoldenSetTest.java`
- Create: `src/test/java/com/example/springbootrag/eval/RetrievalEvalTest.java`
- Modify: `pom.xml` (surefire excludedGroups)

**Interfaces:**
- Consumes: `IngestService.ingestMarkdown`, `SearchService.search`, `SearchHit`.
- Produces: `record GoldenEntry(String question, String expectedDocId, String expectedHeadingPath)`; `GoldenSet.load()` static loader. Task 9 reuses both.

Corpus = this repo's own `docs/**/*.md` files, ingested with REAL Ollama embeddings. Prereq: Docker + Ollama running with `nomic-embed-text`.

- [ ] **Step 1: Exclude eval tags from the normal build**

In the surefire plugin `<configuration>` (KEEP the existing `api.version` block):

```xml
<configuration>
    <excludedGroups>eval,eval-judge</excludedGroups>
    <systemPropertyVariables>
        <api.version>1.44</api.version>
    </systemPropertyVariables>
</configuration>
```

Manual run command (used in later steps): `./mvnw test "-Dgroups=eval" "-DexcludedGroups="`

- [ ] **Step 2: Golden set loader + failing unit test**

`GoldenEntry.java`:

```java
package com.example.springbootrag.eval;

/**
 * One eval case: a question and where the right answer lives.
 * expectedHeadingPath is optional (null = any chunk of the doc counts as a hit).
 */
public record GoldenEntry(String question, String expectedDocId, String expectedHeadingPath) {}
```

`GoldenSet.java` (SnakeYAML is already on the classpath via Spring Boot):

```java
package com.example.springbootrag.eval;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

public final class GoldenSet {

    private GoldenSet() {}

    public static List<GoldenEntry> load() {
        try (InputStream in = GoldenSet.class.getResourceAsStream("/eval/golden.yaml")) {
            if (in == null) {
                throw new IllegalStateException("eval/golden.yaml not found on test classpath");
            }
            List<Map<String, String>> raw = new Yaml().load(in);
            return raw.stream()
                    .map(m -> new GoldenEntry(
                            m.get("question"),
                            m.get("expectedDocId"),
                            m.get("expectedHeadingPath")))
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("could not load golden set", e);
        }
    }
}
```

`GoldenSetTest.java` (runs in the normal build - guards the file format):

```java
package com.example.springbootrag.eval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GoldenSetTest {

    @Test
    void loadsEntriesWithRequiredFields() {
        var entries = GoldenSet.load();
        assertThat(entries).hasSizeGreaterThanOrEqualTo(10);
        assertThat(entries).allSatisfy(e -> {
            assertThat(e.question()).isNotBlank();
            assertThat(e.expectedDocId()).isNotBlank();
        });
    }
}
```

- [ ] **Step 3: Author the golden set**

`src/test/resources/eval/golden.yaml`. `expectedDocId` = filename without `.md`, sanitized like `DocumentController.sanitizeDocId` (non `[a-zA-Z0-9._-]` chars become `-`). These 12 starter entries target this repo's real docs - VERIFY each expectedDocId against the actual files under `docs/` before finalizing, and adjust/extend to ~20 entries by skimming the docs for distinctive facts:

```yaml
- question: "Which library loads the cross-encoder reranker model?"
  expectedDocId: "2026-06-17-rag-roadmap-and-real-project-pipeline-design"
- question: "Why is Neo4j not added up front for graph RAG?"
  expectedDocId: "2026-06-17-rag-roadmap-and-real-project-pipeline-design"
- question: "What is the enriched-chunk pattern for indexing?"
  expectedDocId: "2026-06-17-rag-roadmap-and-real-project-pipeline-design"
- question: "What stages make up the real project pipeline?"
  expectedDocId: "2026-06-17-rag-roadmap-and-real-project-pipeline-design"
- question: "Which RAG methods are parked for later?"
  expectedDocId: "2026-06-17-rag-roadmap-and-real-project-pipeline-design"
- question: "What embedding model and dimension does the sandbox use?"
  expectedDocId: "2026-06-13-springboot-rag-design"
- question: "How does reciprocal rank fusion combine keyword and vector results?"
  expectedDocId: "2026-06-13-springboot-rag-design"
- question: "What does the compare endpoint return?"
  expectedDocId: "2026-06-13-springboot-rag-design"
- question: "Which Postgres extension provides vector similarity search?"
  expectedDocId: "2026-06-13-springboot-rag-design"
- question: "What went wrong with the DJL maven coordinates?"
  expectedDocId: "implementation-notes"
- question: "How do I run the integration tests?"
  expectedDocId: "README"
- question: "What chunking strategy does the knowledge base use for markdown?"
  expectedDocId: "2026-07-02-knowledge-base-design"
```

Run: `./mvnw -q test -Dtest=GoldenSetTest`
Expected: PASS.

- [ ] **Step 4: RetrievalEvalTest**

```java
package com.example.springbootrag.eval;

import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.service.IngestService;
import com.example.springbootrag.service.SearchService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.qdrant.QdrantContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retrieval quality report. NOT part of the normal build.
 * Prereqs: Docker running, Ollama at localhost:11434 with nomic-embed-text pulled.
 * Run: ./mvnw test "-Dgroups=eval" "-DexcludedGroups="
 */
@SpringBootTest
@Testcontainers
@Tag("eval")
class RetrievalEvalTest {

    static final List<String> BACKENDS = List.of("fts", "pgvector", "qdrant", "hybrid", "rerank");
    static final int TOP_K = 10;

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("ragdb").withUsername("rag").withPassword("rag");

    @Container
    static QdrantContainer qdrant =
            new QdrantContainer(DockerImageName.parse("qdrant/qdrant:v1.9.0"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.qdrant.host", qdrant::getHost);
        registry.add("app.qdrant.port", qdrant::getGrpcPort);
        // NO fake embedding config: eval uses the real Ollama provider.
    }

    @Autowired IngestService ingestService;
    @Autowired SearchService searchService;

    @Test
    void retrievalMetricsReport() throws Exception {
        int docs = ingestCorpus();
        List<GoldenEntry> golden = GoldenSet.load();
        assertThat(docs).isGreaterThan(0);
        assertThat(golden).isNotEmpty();

        System.out.printf("%nRetrieval eval: %d docs, %d questions, topK=%d%n", docs, golden.size(), TOP_K);
        System.out.printf("%-10s %10s %10s %10s%n", "backend", "recall@5", "MRR", "hit@1");
        for (String backend : BACKENDS) {
            double recall5 = 0, mrr = 0, hit1 = 0;
            for (GoldenEntry e : golden) {
                List<SearchHit> hits = searchService.search(backend, e.question(), TOP_K);
                int rank = rankOfExpected(hits, e); // 1-based, 0 = not found
                if (rank >= 1 && rank <= 5) recall5++;
                if (rank >= 1) mrr += 1.0 / rank;
                if (rank == 1) hit1++;
            }
            int n = golden.size();
            System.out.printf("%-10s %10.3f %10.3f %10.3f%n",
                    backend, recall5 / n, mrr / n, hit1 / n);
        }
    }

    /** Ingest every markdown file under docs/ with the markdown chunker. Returns doc count. */
    private int ingestCorpus() throws Exception {
        int count = 0;
        try (Stream<Path> paths = Files.walk(Path.of("docs"))) {
            for (Path p : paths.filter(p -> p.toString().endsWith(".md")).toList()) {
                String name = p.getFileName().toString();
                String docId = name.substring(0, name.length() - 3).replaceAll("[^a-zA-Z0-9._-]", "-");
                ingestService.ingestMarkdown(docId, name, Files.readString(p));
                count++;
            }
        }
        // README.md at repo root is part of the corpus too
        ingestService.ingestMarkdown("README", "README.md", Files.readString(Path.of("README.md")));
        return count + 1;
    }

    private static int rankOfExpected(List<SearchHit> hits, GoldenEntry e) {
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            boolean docMatch = h.docId().equals(e.expectedDocId());
            boolean headingMatch = e.expectedHeadingPath() == null
                    || (h.headingPath() != null && h.headingPath().startsWith(e.expectedHeadingPath()));
            if (docMatch && headingMatch) {
                return i + 1;
            }
        }
        return 0;
    }
}
```

- [ ] **Step 5: Run the eval**

Run: `./mvnw test "-Dgroups=eval" "-DexcludedGroups="`
Expected: metrics table prints; sanity-check that `hybrid` recall@5 is well above 0. If a docId never matches, the golden `expectedDocId` is probably wrong - print `hits.get(0).docId()` to debug and fix golden.yaml.

Also run: `./mvnw -q test`
Expected: normal build still green and does NOT run `RetrievalEvalTest` (check output for its absence).

- [ ] **Step 6: Checkpoint - report task done to user (include the metrics table in the report). Do NOT run git commands; user commits manually.**

---

### Task 9: Faithfulness judge eval

**Files:**
- Create: `src/test/java/com/example/springbootrag/eval/FaithfulnessEvalTest.java`

**Interfaces:**
- Consumes: `AskService.ask`, `ChatProvider.chat`, `GoldenSet.load()`, `IngestService.ingestMarkdown`.

Prereqs: Docker + Ollama with BOTH `nomic-embed-text` and the chat model from Task 5.

- [ ] **Step 1: Implement (report harness, no TDD cycle - it IS the test)**

```java
package com.example.springbootrag.eval;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.service.AskService;
import com.example.springbootrag.service.IngestService;
import com.example.springbootrag.web.dto.AskResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.qdrant.QdrantContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Answer faithfulness smoke report using the local LLM as judge.
 * KNOWN LIMITATION: small local judges are noisy - treat results as a smoke
 * signal, not ground truth. RAGAS with a strong judge replaces this later.
 * Run: ./mvnw test "-Dgroups=eval-judge" "-DexcludedGroups="
 */
@SpringBootTest
@Testcontainers
@Tag("eval-judge")
class FaithfulnessEvalTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("ragdb").withUsername("rag").withPassword("rag");

    @Container
    static QdrantContainer qdrant =
            new QdrantContainer(DockerImageName.parse("qdrant/qdrant:v1.9.0"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.qdrant.host", qdrant::getHost);
        registry.add("app.qdrant.port", qdrant::getGrpcPort);
    }

    static final String JUDGE_SYSTEM = """
            You are grading a retrieval-augmented answer. Decide whether every factual claim \
            in the answer is supported by the provided context chunks. Reply with exactly one \
            word: yes, partial, or no.""";

    @Autowired IngestService ingestService;
    @Autowired AskService askService;
    @Autowired ChatProvider chat;

    @Test
    void faithfulnessReport() throws Exception {
        ingestCorpus();
        List<GoldenEntry> golden = GoldenSet.load();
        assertThat(golden).isNotEmpty();

        int yes = 0, partial = 0, no = 0, unparsed = 0;
        for (GoldenEntry e : golden) {
            AskResponse resp = askService.ask(e.question());
            StringBuilder ctx = new StringBuilder();
            resp.sources().forEach(s -> ctx.append('[').append(s.index()).append("] ")
                    .append(s.content()).append("\n\n"));
            String verdict = chat.chat(JUDGE_SYSTEM,
                            "Context chunks:\n" + ctx + "\nQuestion: " + e.question()
                                    + "\nAnswer: " + resp.answer())
                    .trim().toLowerCase(Locale.ROOT);
            String head = verdict.split("\\W+")[0];
            switch (head) {
                case "yes" -> yes++;
                case "partial" -> partial++;
                case "no" -> no++;
                default -> unparsed++;
            }
            System.out.printf("[%s] %s%n", head, e.question());
        }
        System.out.printf("%nFaithfulness (local judge, smoke signal only): "
                        + "yes=%d partial=%d no=%d unparsed=%d of %d%n",
                yes, partial, no, unparsed, golden.size());
        assertThat(yes + partial + no + unparsed).isEqualTo(golden.size());
    }

    private void ingestCorpus() throws Exception {
        try (Stream<Path> paths = Files.walk(Path.of("docs"))) {
            for (Path p : paths.filter(p -> p.toString().endsWith(".md")).toList()) {
                String name = p.getFileName().toString();
                String docId = name.substring(0, name.length() - 3).replaceAll("[^a-zA-Z0-9._-]", "-");
                ingestService.ingestMarkdown(docId, name, Files.readString(p));
            }
        }
        ingestService.ingestMarkdown("README", "README.md", Files.readString(Path.of("README.md")));
    }
}
```

- [ ] **Step 2: Run it**

Run: `./mvnw test "-Dgroups=eval-judge" "-DexcludedGroups="`
Expected: per-question verdicts + summary counts print. Slow (one embed pass + two LLM calls per question).

Run: `./mvnw -q test`
Expected: normal build green, judge test not executed.

- [ ] **Step 3: Checkpoint - report task done to user (include the summary counts). Do NOT run git commands; user commits manually.**

---

### Task 10: Documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/implementation-notes.md`

- [ ] **Step 1: README**

Add a "Knowledge base" section documenting: the UI at `http://localhost:8080/`, the new endpoints (`POST/GET/DELETE /documents`, `GET /ask`), the chat model prerequisite (`ollama pull <model from Task 5>`), and the eval commands:

```
./mvnw test "-Dgroups=eval" "-DexcludedGroups="        # retrieval metrics (needs Docker + Ollama embeddings)
./mvnw test "-Dgroups=eval-judge" "-DexcludedGroups="  # faithfulness smoke report (also needs the chat model)
```

- [ ] **Step 2: implementation-notes**

Append a dated section covering at least: the `chunk(docId, text)` -> `chunk(text)` signature deviation, the commonmark version actually used, the chat model chosen and why, pipe-table atomicity implemented via text sniffing (`startsWith("|")`) instead of a gfm-tables extension (avoided a second dependency), and any deviations that surfaced during Tasks 1-9.

- [ ] **Step 3: Final full verification**

Run: `./mvnw -q test`
Expected: ALL PASS.

- [ ] **Step 4: Checkpoint - report the whole plan done to user. Do NOT run git commands; user commits manually.**
