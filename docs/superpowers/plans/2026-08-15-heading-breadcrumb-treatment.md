# Heading Breadcrumb Treatment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the markdown heading breadcrumb's treatment switchable across five styles, then measure all five against the golden retrieval set to find out what the breadcrumb is actually worth.

**Architecture:** A pure `HeadingStyle` enum renders the breadcrumb; `MarkdownChunker` composes it into chunk text as it does today, so the existing 2000-char embed budget keeps accounting for it and `capToBudget` stays untouched. Only the `EMBED_ONLY` style differs between embedded and stored text, and it is implemented by stripping a known prefix in `IngestService` - storage only ever shrinks.

**Tech Stack:** Java 21 (target), Spring Boot 3.5.6, Maven wrapper (`./mvnw`), JUnit 5, AssertJ, Testcontainers (Postgres pgvector/pg16, Qdrant v1.9.0).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-15-heading-breadcrumb-treatment-design.md`. Read it before starting.
- Build command is `./mvnw`, never `mvn`.
- **No git commits.** The user's CLAUDE.md forbids `git add` / `git commit` unless asked in-session. The writing-plans template normally ends each task with a commit; those steps are deliberately replaced with a verification step. Do not commit.
- Default behavior must not change. `heading-style: full` must reproduce today's output byte for byte, proven by the existing `MarkdownChunkerTest` passing unmodified.
- `chunk.headingPath()` always carries the **full** breadcrumb in every style. It is the eval's matching key (`RetrievalEvalTest.java:108`).
- Comments in English. No Lombok. No new dependencies.
- Never use the character `-` U+2014 in any file.

## File Structure

| File | Responsibility |
|---|---|
| `src/main/java/.../chunk/HeadingStyle.java` (create) | The enum and one pure `render` function. No Spring, no I/O. |
| `src/main/java/.../config/ChunkProperties.java` (create) | Binds `app.chunk.*`. |
| `src/main/java/.../config/ChunkConfig.java` (create) | Registers the properties bean. Nothing else. |
| `src/main/java/.../chunk/MarkdownChunker.java` (modify) | Composes the rendered breadcrumb instead of the hardcoded full path. |
| `src/main/java/.../service/IngestService.java` (modify) | Builds the chunker from config; strips the prefix for `EMBED_ONLY` before storing. |
| `src/main/resources/application.yml` (modify) | The two new knobs, defaulted to today's behavior. |
| `src/test/java/.../chunk/HeadingStyleTest.java` (create) | Pure unit coverage of every style and degenerate input. |
| `src/test/java/.../chunk/MarkdownChunkerTest.java` (modify) | Adds per-style tests. Existing tests must not be edited. |
| `src/test/java/.../integration/HeadingStyleStorageIntegrationTest.java` (create) | Proves embedded text and stored text diverge only under `EMBED_ONLY`. |
| `src/test/java/.../eval/HeadingStyleEvalTest.java` (create) | The 5x6 measurement. Tagged, excluded from the normal build. |
| `pom.xml` (modify) | Adds `eval-heading` to `<excludedGroups>`. |

---

### Task 1: HeadingStyle enum and its renderer

**Files:**
- Create: `src/main/java/com/example/springbootrag/chunk/HeadingStyle.java`
- Test: `src/test/java/com/example/springbootrag/chunk/HeadingStyleTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `enum HeadingStyle { FULL, DEEPEST, PLAIN, NONE, EMBED_ONLY }` and
  `public static String render(HeadingStyle style, String headingPath, int deepestLevels)`.
  Returns the breadcrumb with no trailing separator, or `""` when there is nothing to render.
  Task 2 and Task 3 both call it.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/springbootrag/chunk/HeadingStyleTest.java`:

```java
package com.example.springbootrag.chunk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit test for breadcrumb rendering - no Spring context needed. */
class HeadingStyleTest {

    private static final String PATH = "# Guide > ## Setup > ### Flags";

    @Test
    void fullKeepsThePathUnchanged() {
        assertThat(HeadingStyle.render(HeadingStyle.FULL, PATH, 2)).isEqualTo(PATH);
    }

    @Test
    void embedOnlyRendersIdenticallyToFull() {
        assertThat(HeadingStyle.render(HeadingStyle.EMBED_ONLY, PATH, 2)).isEqualTo(PATH);
    }

    @Test
    void noneRendersNothing() {
        assertThat(HeadingStyle.render(HeadingStyle.NONE, PATH, 2)).isEmpty();
    }

    @Test
    void deepestKeepsOnlyTheDeepestLevels() {
        assertThat(HeadingStyle.render(HeadingStyle.DEEPEST, PATH, 2))
                .isEqualTo("## Setup > ### Flags");
        assertThat(HeadingStyle.render(HeadingStyle.DEEPEST, PATH, 1))
                .isEqualTo("### Flags");
    }

    @Test
    void deepestLeavesAShallowPathWhole() {
        assertThat(HeadingStyle.render(HeadingStyle.DEEPEST, "# Guide", 2)).isEqualTo("# Guide");
        assertThat(HeadingStyle.render(HeadingStyle.DEEPEST, "# Guide > ## Setup", 2))
                .isEqualTo("# Guide > ## Setup");
    }

    @Test
    void deepestTreatsNonPositiveLevelsAsOne() {
        assertThat(HeadingStyle.render(HeadingStyle.DEEPEST, PATH, 0)).isEqualTo("### Flags");
        assertThat(HeadingStyle.render(HeadingStyle.DEEPEST, PATH, -3)).isEqualTo("### Flags");
    }

    @Test
    void plainStripsTheHashMarks() {
        assertThat(HeadingStyle.render(HeadingStyle.PLAIN, PATH, 2))
                .isEqualTo("Guide > Setup > Flags");
    }

    @Test
    void nullOrBlankPathRendersNothingForEveryStyle() {
        for (HeadingStyle style : HeadingStyle.values()) {
            assertThat(HeadingStyle.render(style, null, 2)).isEmpty();
            assertThat(HeadingStyle.render(style, "   ", 2)).isEmpty();
        }
    }

    @Test
    void aHeadingContainingAGreaterThanSignIsNotSplitOnIt() {
        // MarkdownChunker joins levels with " > " (spaces included), so a bare '>' inside a
        // heading title must survive.
        String path = "# A>B > ## C";
        assertThat(HeadingStyle.render(HeadingStyle.DEEPEST, path, 1)).isEqualTo("## C");
        assertThat(HeadingStyle.render(HeadingStyle.PLAIN, path, 2)).isEqualTo("A>B > C");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test "-Dtest=HeadingStyleTest"`
Expected: FAIL - compilation error, `HeadingStyle` does not exist.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/example/springbootrag/chunk/HeadingStyle.java`:

```java
package com.example.springbootrag.chunk;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * How a heading breadcrumb is rendered into chunk text before embedding.
 *
 * <p>Kept pure and Spring-free so every style can be tested without a context. The breadcrumb has
 * always been prepended to chunk text (MarkdownChunker), but its shape was never measured - these
 * styles exist so the eval harness can price each variant. See
 * docs/superpowers/specs/2026-08-15-heading-breadcrumb-treatment-design.md
 */
public enum HeadingStyle {
    /** Today's behaviour: the whole path, hash marks included. */
    FULL,
    /** Only the deepest N levels - the root title repeats across the whole document. */
    DEEPEST,
    /** The whole path with the hash marks stripped. */
    PLAIN,
    /** No breadcrumb at all - the honest baseline. */
    NONE,
    /** Renders like FULL; IngestService strips it from the stored text. */
    EMBED_ONLY;

    /** Matches how MarkdownChunker.breadcrumb() joins levels - spaces included, so a bare '>' inside a heading survives. */
    private static final String SEPARATOR = " > ";
    private static final Pattern SPLIT = Pattern.compile(Pattern.quote(SEPARATOR));

    /**
     * @param headingPath the full breadcrumb, or null for content that sits before any heading
     * @param deepestLevels read only by {@link #DEEPEST}; values below 1 are treated as 1
     * @return the breadcrumb text with no trailing separator, or "" when there is nothing to render
     */
    public static String render(HeadingStyle style, String headingPath, int deepestLevels) {
        if (style == NONE || headingPath == null || headingPath.isBlank()) {
            return "";
        }
        String[] levels = SPLIT.split(headingPath, -1);
        if (style == DEEPEST) {
            int keep = Math.max(1, deepestLevels);
            if (levels.length > keep) {
                levels = Arrays.copyOfRange(levels, levels.length - keep, levels.length);
            }
        }
        if (style == PLAIN) {
            for (int i = 0; i < levels.length; i++) {
                levels[i] = stripHashMarks(levels[i]);
            }
        }
        return String.join(SEPARATOR, levels);
    }

    private static String stripHashMarks(String level) {
        int i = 0;
        while (i < level.length() && level.charAt(i) == '#') {
            i++;
        }
        return level.substring(i).strip();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test "-Dtest=HeadingStyleTest"`
Expected: PASS, 9 tests.

- [ ] **Step 5: Verify nothing else broke**

Run: `./mvnw test`
Expected: the whole suite still green. No production code calls `HeadingStyle` yet, so this is a pure addition.

---

### Task 2: Config binding and chunker wiring

**Files:**
- Create: `src/main/java/com/example/springbootrag/config/ChunkProperties.java`
- Create: `src/main/java/com/example/springbootrag/config/ChunkConfig.java`
- Modify: `src/main/java/com/example/springbootrag/chunk/MarkdownChunker.java:34-37` (constructor) and `:112-116` (composition)
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/example/springbootrag/chunk/MarkdownChunkerTest.java` (append only)

**Interfaces:**
- Consumes: `HeadingStyle.render(style, headingPath, deepestLevels)` from Task 1.
- Produces:
  - `new MarkdownChunker(int maxWords, WordWindowChunker fallback, HeadingStyle style, int deepestLevels)`, with the existing `(int, WordWindowChunker)` constructor kept as an overload delegating to `(maxWords, fallback, HeadingStyle.FULL, 2)`.
  - `ChunkProperties` with `getHeadingStyle()`, `setHeadingStyle(HeadingStyle)`, `getDeepestLevels()`, `setDeepestLevels(int)`. Task 3 and Task 4 both use it.

- [ ] **Step 1: Write the failing tests**

Append to `src/test/java/com/example/springbootrag/chunk/MarkdownChunkerTest.java`, inside the existing class, above the closing brace. Do not edit any existing test - their passing unchanged is the proof that the default is untouched.

```java
    /** Three heading levels, one short paragraph - the fixture every style test below shares. */
    private static final String NESTED_MD = """
            # Guide

            ## Setup

            ### Flags

            Pass the verbose flag.
            """;

    private static List<Chunk> chunkWith(HeadingStyle style, int deepestLevels) {
        return new MarkdownChunker(30, new WordWindowChunker(20, 5), style, deepestLevels)
                .chunk(NESTED_MD);
    }

    @Test
    void defaultConstructorStillProducesTheFullBreadcrumb() {
        List<Chunk> chunks = chunker.chunk(NESTED_MD);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).startsWith("# Guide > ## Setup > ### Flags\n\n");
    }

    @Test
    void fullStyleMatchesTheDefaultConstructor() {
        assertThat(chunkWith(HeadingStyle.FULL, 2).get(0).text())
                .isEqualTo(chunker.chunk(NESTED_MD).get(0).text());
    }

    @Test
    void deepestStyleDropsTheAncestors() {
        assertThat(chunkWith(HeadingStyle.DEEPEST, 2).get(0).text())
                .startsWith("## Setup > ### Flags\n\n")
                .contains("Pass the verbose flag.");
    }

    @Test
    void plainStyleDropsTheHashMarks() {
        assertThat(chunkWith(HeadingStyle.PLAIN, 2).get(0).text())
                .startsWith("Guide > Setup > Flags\n\n")
                .contains("Pass the verbose flag.");
    }

    @Test
    void noneStyleEmitsThePieceAlone() {
        assertThat(chunkWith(HeadingStyle.NONE, 2).get(0).text())
                .isEqualTo("Pass the verbose flag.");
    }

    @Test
    void embedOnlyStyleComposesLikeFull() {
        assertThat(chunkWith(HeadingStyle.EMBED_ONLY, 2).get(0).text())
                .startsWith("# Guide > ## Setup > ### Flags\n\n");
    }

    @Test
    void headingPathStaysTheFullPathInEveryStyle() {
        for (HeadingStyle style : HeadingStyle.values()) {
            assertThat(chunkWith(style, 2).get(0).headingPath())
                    .as("headingPath must stay the full path for %s - the eval matches on it", style)
                    .isEqualTo("# Guide > ## Setup > ### Flags");
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test "-Dtest=MarkdownChunkerTest"`
Expected: FAIL - compilation error, no four-argument `MarkdownChunker` constructor.

- [ ] **Step 3: Add the constructor and the composition change**

In `src/main/java/com/example/springbootrag/chunk/MarkdownChunker.java`, replace the constructor at lines 34-37:

```java
    private final HeadingStyle style;
    private final int deepestLevels;

    /** Default styling: the full breadcrumb, exactly as this chunker has always emitted it. */
    public MarkdownChunker(int maxWords, WordWindowChunker fallback) {
        this(maxWords, fallback, HeadingStyle.FULL, 2);
    }

    public MarkdownChunker(int maxWords, WordWindowChunker fallback,
                           HeadingStyle style, int deepestLevels) {
        this.maxWords = maxWords;
        this.fallback = fallback;
        this.style = style;
        this.deepestLevels = deepestLevels;
    }
```

Then replace lines 112-116 inside `flushSection`:

```java
        String headingPath = breadcrumb.isEmpty() ? null : breadcrumb;
        // headingPath keeps the FULL path in every style - it is a search filter and the eval's
        // matching key (RetrievalEvalTest.rankOfExpected). Only the embedded text varies.
        String rendered = HeadingStyle.render(style, headingPath, deepestLevels);
        for (String piece : pieces) {
            String text = rendered.isEmpty() ? piece : rendered + "\n\n" + piece;
            out.add(new Chunk(text, headingPath, position[0]++));
        }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw test "-Dtest=MarkdownChunkerTest"`
Expected: PASS - the 9 original tests plus the 7 new ones, 16 total.

- [ ] **Step 5: Add the properties classes**

Create `src/main/java/com/example/springbootrag/config/ChunkProperties.java`:

```java
package com.example.springbootrag.config;

import com.example.springbootrag.chunk.HeadingStyle;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Chunking knobs. See docs/superpowers/specs/2026-08-15-heading-breadcrumb-treatment-design.md */
@ConfigurationProperties(prefix = "app.chunk")
public class ChunkProperties {

    /**
     * FULL reproduces the pre-experiment behaviour byte for byte. Every other value changes the
     * text that was embedded, so changing this requires a full re-ingest - it is a deploy-time
     * knob, not a live toggle.
     */
    private HeadingStyle headingStyle = HeadingStyle.FULL;

    /** How many of the deepest heading levels DEEPEST keeps. Ignored by every other style. */
    private int deepestLevels = 2;

    public HeadingStyle getHeadingStyle() { return headingStyle; }
    public void setHeadingStyle(HeadingStyle headingStyle) { this.headingStyle = headingStyle; }
    public int getDeepestLevels() { return deepestLevels; }
    public void setDeepestLevels(int deepestLevels) { this.deepestLevels = deepestLevels; }
}
```

Create `src/main/java/com/example/springbootrag/config/ChunkConfig.java`:

```java
package com.example.springbootrag.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ChunkProperties.class)
public class ChunkConfig {
}
```

- [ ] **Step 6: Add the config block**

In `src/main/resources/application.yml`, insert directly after the `app.security` block and before `app.embedding`:

```yaml
  chunk:
    # full reproduces today's behaviour exactly. Changing this invalidates every existing vector
    # and requires a full re-ingest - see the heading-breadcrumb-treatment spec.
    heading-style: full        # full | deepest | plain | none | embed-only
    deepest-levels: 2          # only read by heading-style: deepest
```

- [ ] **Step 7: Verify the whole suite**

Run: `./mvnw test`
Expected: green. `ChunkProperties` is bound but nothing consumes it yet, so behavior is unchanged.

---

### Task 3: IngestService builds the chunker and strips for EMBED_ONLY

**Files:**
- Modify: `src/main/java/com/example/springbootrag/service/IngestService.java:49` (chunker field), `:52-74` (constructor), `:201-216` (storage loop)
- Test: `src/test/java/com/example/springbootrag/integration/HeadingStyleStorageIntegrationTest.java` (create)

**Interfaces:**
- Consumes: `ChunkProperties` and the four-argument `MarkdownChunker` constructor from Task 2, `HeadingStyle.render` from Task 1.
- Produces: `IngestService` honouring `app.chunk.*`. Task 4 mutates the autowired `ChunkProperties` between eval passes and relies on the chunker being rebuilt per ingest call.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/springbootrag/integration/HeadingStyleStorageIntegrationTest.java`:

```java
package com.example.springbootrag.integration;

import com.example.springbootrag.config.ChunkProperties;
import com.example.springbootrag.chunk.HeadingStyle;
import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.service.IngestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.qdrant.QdrantContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The containment property: the breadcrumb reaches the embedder in every style, but reaches
 * STORAGE only when the style is not EMBED_ONLY. Stored text feeds tsv, the reranker, the answer
 * prompt and the UI, so a leak here would silently widen the experiment's blast radius.
 */
// edges=structural: no ChatProvider stub here, so pin the mode to avoid a real-Ollama call.
@SpringBootTest(properties = "app.graph.edges=structural")
@Testcontainers
class HeadingStyleStorageIntegrationTest {

    private static final String MD = """
            # Guide

            ## Setup

            Pass the verbose flag.
            """;
    private static final String BREADCRUMB = "# Guide > ## Setup";

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

    /** Records what was handed to the embedder so the test can assert on it. */
    static final List<String> EMBEDDED = new ArrayList<>();

    @TestConfiguration
    static class RecordingEmbeddingConfig {
        @Bean
        @Primary
        EmbeddingProvider recordingEmbeddingProvider() {
            return new EmbeddingProvider() {
                @Override public float[] embed(String text) {
                    EMBEDDED.add(text);
                    float[] v = new float[768];
                    v[0] = 1f;
                    return v;
                }
                @Override public int dimension() { return 768; }
            };
        }
    }

    @Autowired IngestService ingestService;
    @Autowired ChunkProperties chunkProps;
    @Autowired JdbcTemplate jdbc;

    private String storedContentOf(String docId) {
        return jdbc.queryForObject(
                "select content from chunks where doc_id = ? order by chunk_index limit 1",
                String.class, docId);
    }

    @Test
    void fullStyleKeepsTheBreadcrumbInBothEmbeddedAndStoredText() {
        chunkProps.setHeadingStyle(HeadingStyle.FULL);
        EMBEDDED.clear();

        ingestService.ingestMarkdown("full-doc", "guide.md", MD);

        assertThat(EMBEDDED).isNotEmpty();
        assertThat(EMBEDDED.get(0)).startsWith(BREADCRUMB);
        assertThat(storedContentOf("full-doc")).startsWith(BREADCRUMB);
    }

    @Test
    void embedOnlyStyleKeepsTheBreadcrumbOutOfStorage() {
        chunkProps.setHeadingStyle(HeadingStyle.EMBED_ONLY);
        EMBEDDED.clear();

        ingestService.ingestMarkdown("embed-only-doc", "guide.md", MD);

        assertThat(EMBEDDED).isNotEmpty();
        assertThat(EMBEDDED.get(0)).startsWith(BREADCRUMB);          // reached the embedder
        String stored = storedContentOf("embed-only-doc");
        assertThat(stored).doesNotContain(BREADCRUMB)                 // did NOT reach storage
                .isEqualTo("Pass the verbose flag.");
    }

    @Test
    void noneStyleKeepsTheBreadcrumbOutOfBoth() {
        chunkProps.setHeadingStyle(HeadingStyle.NONE);
        EMBEDDED.clear();

        ingestService.ingestMarkdown("none-doc", "guide.md", MD);

        assertThat(EMBEDDED).isNotEmpty();
        assertThat(EMBEDDED.get(0)).doesNotContain(BREADCRUMB);
        assertThat(storedContentOf("none-doc")).doesNotContain(BREADCRUMB);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test "-Dtest=HeadingStyleStorageIntegrationTest"`
Expected: FAIL. `fullStyleKeeps...` passes by accident, but `embedOnlyStyle...` fails because the stored content still contains the breadcrumb - `IngestService` does not know about styles yet.

- [ ] **Step 3: Wire ChunkProperties into IngestService**

In `src/main/java/com/example/springbootrag/service/IngestService.java`:

Add the imports:

```java
import com.example.springbootrag.chunk.HeadingStyle;
import com.example.springbootrag.config.ChunkProperties;
```

Delete the inline field initialiser at line 49 (`private final MarkdownChunker markdown = ...`) and add a field beside the other properties:

```java
    private final ChunkProperties chunkProps;
```

Add `ChunkProperties chunkProps` as the final constructor parameter and assign it:

```java
        this.chunkProps = chunkProps;
```

Add the factory below the constructor. The chunker is rebuilt per document rather than cached so a
config change takes effect without restarting the context - the eval harness depends on this, and
the cost is one object per document, not per chunk:

```java
    /** Rebuilt per document so a heading-style change takes effect without a context restart. */
    private MarkdownChunker markdownChunker() {
        return new MarkdownChunker(300, new WordWindowChunker(120, 20),
                chunkProps.getHeadingStyle(), chunkProps.getDeepestLevels());
    }
```

Replace the one use of the old field in `ingestMarkdown` (line 126, `markdown.chunk(markdownText)`):

```java
        int stored = ingestChunks(projectId, docId, sourceFile, markdownChunker().chunk(markdownText),
                updatedAt, allowedGroups, null, null, scanForSecrets);
```

- [ ] **Step 4: Add the storage strip**

In the same file, add the helper beside `joinedText`:

```java
    /**
     * What actually gets stored. Identical to the embedded text except under EMBED_ONLY, where the
     * breadcrumb is removed so it reaches the vector but not content/tsv/reranker/prompt/UI.
     *
     * <p>Removal only, never addition, so stored text can never exceed the embed budget. A chunk
     * whose prefix is missing - capToBudget splits an oversized chunk, and only the first piece
     * keeps the breadcrumb - is stored unchanged rather than treated as an error.
     */
    private String storeText(Chunk chunk) {
        if (chunkProps.getHeadingStyle() != HeadingStyle.EMBED_ONLY || chunk.headingPath() == null) {
            return chunk.text();
        }
        String prefix = HeadingStyle.render(HeadingStyle.FULL, chunk.headingPath(),
                chunkProps.getDeepestLevels()) + "\n\n";
        return chunk.text().startsWith(prefix) ? chunk.text().substring(prefix.length()) : chunk.text();
    }
```

Then in the storage loop (lines 201-216), use it for both stores while the embedding keeps the full text:

```java
            String stored = storeText(chunk);
            float[] vec = embeddings.embed(chunk.text());
            long id = pgVector.insert(projectId, docId, chunk.position(), stored,
                    sourceFile, chunk.headingPath(), vec, updatedAt, groups, docType, meta);
            try {
                qdrant.upsert(id, projectId, docId, chunk.position(), stored,
                        sourceFile, chunk.headingPath(), vec, groups, docType, meta);
            } catch (ExecutionException | InterruptedException e) {
                throw new IllegalStateException("Qdrant upsert failed", e);
            }
```

Leave `extractAndPersist(projectId, id, chunk.text())` as it is - entity extraction reading the breadcrumb is the existing behavior and is not part of this experiment.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw test "-Dtest=HeadingStyleStorageIntegrationTest"`
Expected: PASS, 3 tests.

- [ ] **Step 6: Verify the whole suite**

Run: `./mvnw test`
Expected: green. If `IngestService`'s constructor signature change broke a test that constructs it by hand, add `new ChunkProperties()` as the final argument there - the defaults reproduce today's behavior.

---

### Task 4: The 5x6 eval

**Files:**
- Create: `src/test/java/com/example/springbootrag/eval/HeadingStyleEvalTest.java`
- Modify: `pom.xml:21`

**Interfaces:**
- Consumes: `ChunkProperties` (Task 2), the style-aware `IngestService` (Task 3), and the existing `GoldenSet.load()`, `SearchService.search`, `TestContexts.PUBLIC`.
- Produces: a printed 5x6 table. No assertions on quality - this is a measurement, not a gate.

- [ ] **Step 1: Register the tag so the normal build skips it**

In `pom.xml`, line 21, add `eval-heading` to the list:

```xml
		<excludedGroups>eval,eval-judge,eval-wiki,eval-feedback,eval-records,eval-injection,eval-heading</excludedGroups>
```

- [ ] **Step 2: Write the eval**

Create `src/test/java/com/example/springbootrag/eval/HeadingStyleEvalTest.java`:

```java
package com.example.springbootrag.eval;

import com.example.springbootrag.chunk.HeadingStyle;
import com.example.springbootrag.config.ChunkProperties;
import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.security.TestContexts;
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
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prices every heading-breadcrumb style against the golden set. NOT part of the normal build.
 * Prereqs: Docker running, Ollama at localhost:11434 with nomic-embed-text pulled.
 * Run: ./mvnw test "-Dgroups=eval-heading" "-DexcludedGroups="
 *
 * <p>Cost: five full corpus ingests against real Ollama. Ollama timing swings hard under memory
 * pressure - reap stray JVMs and containers first or the numbers are noise.
 */
@SpringBootTest
@Testcontainers
@Tag("eval-heading")
class HeadingStyleEvalTest {

    static final List<String> BACKENDS = List.of("fts", "pgvector", "qdrant", "hybrid", "rerank", "graph");
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
    @Autowired ChunkProperties chunkProps;

    @Test
    void headingStyleComparisonReport() throws Exception {
        List<GoldenEntry> golden = GoldenSet.load();
        assertThat(golden).isNotEmpty();

        System.out.printf("%nHeading style eval: %d questions, topK=%d%n", golden.size(), TOP_K);
        System.out.printf("%-12s %-10s %10s %10s %10s%n", "style", "backend", "recall@5", "MRR", "hit@1");

        for (HeadingStyle style : HeadingStyle.values()) {
            chunkProps.setHeadingStyle(style);
            int docs = ingestCorpus();   // re-ingest: the embedded text changed
            assertThat(docs).isGreaterThan(0);

            for (String backend : BACKENDS) {
                double recall5 = 0, mrr = 0, hit1 = 0;
                for (GoldenEntry e : golden) {
                    List<SearchHit> hits =
                            searchService.search(TestContexts.PUBLIC, backend, e.question(), TOP_K);
                    int rank = rankOfExpected(hits, e); // 1-based, 0 = not found
                    if (rank >= 1 && rank <= 5) recall5++;
                    if (rank >= 1) mrr += 1.0 / rank;
                    if (rank == 1) hit1++;
                }
                int n = golden.size();
                System.out.printf(Locale.ROOT, "%-12s %-10s %10.3f %10.3f %10.3f%n",
                        style.name().toLowerCase(Locale.ROOT), backend,
                        recall5 / n, mrr / n, hit1 / n);
            }
        }
    }

    /** Ingest every markdown file under docs/ plus the root README. Returns doc count. */
    private int ingestCorpus() throws Exception {
        int count = 0;
        try (Stream<Path> paths = Files.walk(Path.of("docs"))) {
            for (Path p : paths.filter(p -> p.toString().endsWith(".md")).toList()) {
                String rel = Path.of("docs").relativize(p).toString();
                String docId = rel.substring(0, rel.length() - 3).replaceAll("[^a-zA-Z0-9._-]", "-");
                String name = p.getFileName().toString();
                ingestService.ingestMarkdown(docId, name, Files.readString(p));
                count++;
            }
        }
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

`ingestChunks` deletes a document's existing chunks before writing (`IngestService.java:200`), so
re-ingesting the same corpus per style replaces the previous style's vectors rather than
accumulating them. No explicit wipe is needed between passes.

- [ ] **Step 3: Verify the normal build still skips it**

Run: `./mvnw test`
Expected: green, and `HeadingStyleEvalTest` does not appear in the output. If it ran, the tag was not added to `pom.xml` correctly.

- [ ] **Step 4: Run the eval**

Prereqs: Docker running, Ollama up with `nomic-embed-text` pulled, and no stray JVMs or containers competing for memory.

Run: `./mvnw test "-Dgroups=eval-heading" "-DexcludedGroups="`
Expected: a 30-row table, five styles by six backends.

- [ ] **Step 5: Record the result**

Append the table and a one-paragraph reading to `docs/LEARNINGS.md`, following the numbering style already used there. State plainly which style wins, by how much, and whether the difference is large enough to act on. Use the interpretation rules from the spec's "Reading the result" section: `none` is the honest baseline, `fts` should move only for `none` and `embed-only`, `embed-only` versus `full` splits vector benefit from keyword benefit, `plain` versus `full` prices the hash marks.

If the five rows are within noise of each other, say so and keep `full` - that is a real finding and it costs nothing to leave the default alone.

---

## Self-Review

**Spec coverage:** every spec section maps to a task. Modes and `HeadingStyle` -> Task 1. Config, `MarkdownChunker`, and the `headingPath`-stays-full rule -> Task 2. Architecture, `EMBED_ONLY` stripping, and the operational constraint -> Task 3. Tests 1-2 -> Tasks 1-2, test 3 -> Task 3, test 4 and "Reading the result" -> Task 4.

**Failure-mode coverage:** null and blank `headingPath`, shallow path, non-positive `deepestLevels`, and a literal `>` inside a heading are all in `HeadingStyleTest`. The missing-prefix case is handled by `storeText`'s `startsWith` guard and documented in its javadoc. Invalid yaml values are Spring's own startup failure and need no test.

**Type consistency:** `render(HeadingStyle, String, int)` is called with three arguments in `HeadingStyleTest`, `MarkdownChunker.flushSection`, and `IngestService.storeText`. `ChunkProperties` getters are `getHeadingStyle()` and `getDeepestLevels()` everywhere. The four-argument `MarkdownChunker` constructor is used consistently in `MarkdownChunkerTest.chunkWith` and `IngestService.markdownChunker`.

**Known gap accepted:** `MarkdownChunkerTest.chunkWith` needs `HeadingStyle` imported; it is in the same package (`com.example.springbootrag.chunk`), so no import statement is required. `HeadingStyleStorageIntegrationTest` is in `integration` and does import it.
