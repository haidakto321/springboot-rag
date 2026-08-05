# GraphRAG Structural Graph Implementation Plan (Phase 1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `graph` retrieval backend driven by the wiki's own link/hierarchy structure (no LLM), plus per-document recency, exposed alongside the existing backends in `/search` and `/compare`.

**Architecture:** Parse Azure-wiki markdown links + `.order` hierarchy into a `doc_edge` table at ingest. Graph retrieval seeds with the existing `hybrid` backend, expands one hop over `doc_edge` to linked pages, unions the two, and reranks with the existing `Reranker`. A per-document `updated_at` (git commit date, captured by a bulk importer) is carried on `SearchHit` and used as a recency tiebreak. This phase adds NO entity extraction - that is Phase 2 (`2026-07-04-graphrag-semantic.md`).

**Tech Stack:** Java 21, Spring Boot 3.5.6, plain `JdbcTemplate` (no JPA), PostgreSQL + pgvector, JUnit 5 + Testcontainers. Build with `./mvnw` / `mvnw.cmd`. No Lombok. No new dependencies.

## Global Constraints

- Java target 21; build via `./mvnw` (never system `mvn`).
- No new Maven dependencies without asking (repo rule) - this plan adds none.
- Repositories are plain `JdbcTemplate` classes annotated `@Repository`; no JPA/Hibernate.
- `schema.sql` runs on every startup (`spring.sql.init.mode=always`); all DDL must be idempotent (`IF NOT EXISTS` / `ADD COLUMN IF NOT EXISTS`). Spring `ScriptUtils` splits on bare `;` - keep any multi-statement bodies inside single-quoted `DO '...'` blocks (see existing trigger in `schema.sql`).
- Every backend returns `List<SearchHit>`; project/doc scoping uses `List<Long> projectIds, List<String> docIds` where an empty list means "filter absent" (see `DocFilter`).
- Config classes follow the `RerankProperties` + `@EnableConfigurationProperties` pattern.
- Commit messages: conventional commits, English. Do NOT add `Co-Authored-By` trailers (repo rule). Do not run `git add`/`git commit` on the user's behalf unless they ask - steps below show the commit but the user may run them.
- Graph retrieval must never return empty when `hybrid` would have returned hits: fall back to `hybrid`. Graph can only ADD recall, never subtract.

---

## File Structure

- `src/main/resources/schema.sql` - add `doc_edge` table + `chunks.updated_at` column (modify)
- `src/main/java/.../graph/WikiLinkParser.java` - markdown -> outbound link doc-targets (create)
- `src/main/java/.../repository/DocEdgeRepository.java` - `doc_edge` CRUD + neighbor lookup (create)
- `src/main/java/.../config/GraphProperties.java` - `app.graph.*` config (create)
- `src/main/java/.../config/GraphConfig.java` - `@EnableConfigurationProperties` (create)
- `src/main/java/.../model/SearchHit.java` - add nullable `Instant updatedAt` (modify)
- `src/main/java/.../repository/PgVectorRepository.java`, `PgFtsRepository.java`, `QdrantRepository.java` - select/populate `updated_at` in row mappers (modify)
- `src/main/java/.../service/IngestService.java` - capture `updatedAt`, write `doc_edge`, cascade delete (modify)
- `src/main/java/.../service/SearchService.java` - add `"graph"` case + recency tiebreak (modify)
- `src/main/java/.../tool/WikiImporter.java` - bulk dir-walk importer with git dates (create)
- Tests mirror each under `src/test/java/...`

---

### Task 1: Schema - doc_edge table and chunks.updated_at

**Files:**
- Modify: `src/main/resources/schema.sql` (append after the trigger, line 70)
- Test: `src/test/java/com/example/springbootrag/integration/GraphSchemaIntegrationTest.java` (create)

**Interfaces:**
- Produces: table `doc_edge(id, project_id, src_doc, dst_doc, kind, created_at)` with unique `(project_id, src_doc, dst_doc, kind)`; column `chunks.updated_at TIMESTAMPTZ`.

- [ ] **Step 1: Write the failing test**

```java
package com.example.springbootrag.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class GraphSchemaIntegrationTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }

    @Autowired JdbcTemplate jdbc;

    @Test
    void docEdgeTableAndUpdatedAtColumnExist() {
        Integer edgeCols = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'doc_edge'", Integer.class);
        assertThat(edgeCols).isGreaterThanOrEqualTo(6);

        Integer updatedAt = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns " +
                "WHERE table_name = 'chunks' AND column_name = 'updated_at'", Integer.class);
        assertThat(updatedAt).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvnw.cmd -Dtest=GraphSchemaIntegrationTest test`
Expected: FAIL - `doc_edge` count is 0 (table missing) / `updated_at` count is 0.

- [ ] **Step 3: Add the DDL to schema.sql**

Append to `src/main/resources/schema.sql`:

```sql
-- ---- GraphRAG structural graph (Phase 1) ----

ALTER TABLE chunks ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS doc_edge (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id BIGINT NOT NULL,
    src_doc    VARCHAR(255) NOT NULL,
    dst_doc    VARCHAR(255) NOT NULL,
    kind       VARCHAR(32)  NOT NULL,   -- 'link' | 'hierarchy'
    created_at TIMESTAMP DEFAULT now(),
    UNIQUE (project_id, src_doc, dst_doc, kind)
);

CREATE INDEX IF NOT EXISTS idx_doc_edge_src ON doc_edge (project_id, src_doc);
CREATE INDEX IF NOT EXISTS idx_doc_edge_dst ON doc_edge (project_id, dst_doc);

DO '
BEGIN
    ALTER TABLE doc_edge ADD CONSTRAINT fk_doc_edge_project
        FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL;
END
';
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvnw.cmd -Dtest=GraphSchemaIntegrationTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/schema.sql src/test/java/com/example/springbootrag/integration/GraphSchemaIntegrationTest.java
git commit -m "feat(graph): add doc_edge table and chunks.updated_at column"
```

---

### Task 2: WikiLinkParser - extract outbound page links

**Files:**
- Create: `src/main/java/com/example/springbootrag/graph/WikiLinkParser.java`
- Test: `src/test/java/com/example/springbootrag/graph/WikiLinkParserTest.java`

**Interfaces:**
- Produces: `class WikiLinkParser { List<String> outboundDocIds(String markdown) }` - returns sanitized docIds (same rule as `DocumentController.sanitizeDocId`) for each cross-page link, excluding in-page `#anchor` links and `.attachments` image refs. Deduplicated, order-preserving.

- [ ] **Step 1: Write the failing test**

```java
package com.example.springbootrag.graph;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class WikiLinkParserTest {

    private final WikiLinkParser parser = new WikiLinkParser();

    @Test
    void extractsCrossPageLinksAsDocIds() {
        String md = "See [Data](/Data-Migration) and [Arch](/Confluence-Imports/Data-Architecture-Overview).";
        assertThat(parser.outboundDocIds(md))
                .containsExactly("Data-Migration", "Data-Architecture-Overview");
    }

    @Test
    void ignoresAnchorAndAttachmentAndExternalLinks() {
        String md = "[toc](#Section-One) [img](/.attachments/pic.png) [ext](https://example.com/x)";
        assertThat(parser.outboundDocIds(md)).isEmpty();
    }

    @Test
    void deduplicatesRepeatedTargets() {
        String md = "[a](/Same-Page) then [b](/Same-Page)";
        assertThat(parser.outboundDocIds(md)).containsExactly("Same-Page");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvnw.cmd -Dtest=WikiLinkParserTest test`
Expected: FAIL - `WikiLinkParser` does not exist (compile error).

- [ ] **Step 3: Write minimal implementation**

```java
package com.example.springbootrag.graph;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts outbound cross-page links from Azure-wiki markdown as docIds.
 * Keeps only "](/Some/Path)" style page refs; drops "#anchor" in-page jumps,
 * ".attachments" image refs, and external "http(s)" links. The last path
 * segment is sanitized to a docId with the same rule DocumentController uses.
 */
public class WikiLinkParser {

    // Matches the target inside markdown link parens: ](target)
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)]+)\\)");

    public List<String> outboundDocIds(String markdown) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (markdown == null || markdown.isBlank()) {
            return out;
        }
        Matcher m = LINK.matcher(markdown);
        while (m.find()) {
            String target = m.group(1).trim();
            if (target.startsWith("#")) continue;                 // in-page anchor
            if (target.startsWith("http://") || target.startsWith("https://")) continue;
            if (target.contains(".attachments")) continue;        // image/attachment
            if (!target.startsWith("/")) continue;                // only absolute wiki refs
            String pathOnly = target.split("#", 2)[0];            // strip trailing anchor
            String last = pathOnly.substring(pathOnly.lastIndexOf('/') + 1);
            if (last.isBlank()) continue;
            String docId = sanitizeDocId(last);
            if (seen.add(docId)) {
                out.add(docId);
            }
        }
        return out;
    }

    /* Mirror of DocumentController.sanitizeDocId so link targets match stored docIds. */
    static String sanitizeDocId(String segment) {
        String base = segment.endsWith(".md")
                ? segment.substring(0, segment.length() - ".md".length())
                : segment;
        return base.replaceAll("[^a-zA-Z0-9._-]", "-");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvnw.cmd -Dtest=WikiLinkParserTest test`
Expected: PASS (all 3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/springbootrag/graph/WikiLinkParser.java src/test/java/com/example/springbootrag/graph/WikiLinkParserTest.java
git commit -m "feat(graph): parse wiki markdown links into outbound docIds"
```

---

### Task 3: DocEdgeRepository - persist and query structural edges

**Files:**
- Create: `src/main/java/com/example/springbootrag/repository/DocEdgeRepository.java`
- Test: `src/test/java/com/example/springbootrag/integration/DocEdgeRepositoryIntegrationTest.java`

**Interfaces:**
- Consumes: `JdbcTemplate`; table `doc_edge` (Task 1).
- Produces:
  - `void insertLink(long projectId, String srcDoc, String dstDoc)` - upsert `kind='link'` (ON CONFLICT DO NOTHING)
  - `void insertHierarchy(long projectId, String parentDoc, String childDoc)` - upsert `kind='hierarchy'`
  - `List<String> neighbors(long projectId, List<String> srcDocs)` - distinct `dst_doc` for any src in the list (both kinds); empty list -> empty result
  - `void deleteBySrcDoc(long projectId, String srcDoc)` - delete rows where `src_doc = ?`

- [ ] **Step 1: Write the failing test**

```java
package com.example.springbootrag.integration;

import com.example.springbootrag.repository.DocEdgeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class DocEdgeRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }

    @Autowired DocEdgeRepository repo;
    @Autowired JdbcTemplate jdbc;

    private long projectId() {
        return jdbc.queryForObject("SELECT id FROM projects ORDER BY id LIMIT 1", Long.class);
    }

    @Test
    void insertsNeighborsAndDeletesBySrc() {
        long p = projectId();
        repo.insertLink(p, "A", "B");
        repo.insertLink(p, "A", "B");            // idempotent, no duplicate
        repo.insertHierarchy(p, "A", "C");

        assertThat(repo.neighbors(p, List.of("A")))
                .containsExactlyInAnyOrder("B", "C");
        assertThat(repo.neighbors(p, List.of())).isEmpty();

        repo.deleteBySrcDoc(p, "A");
        assertThat(repo.neighbors(p, List.of("A"))).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvnw.cmd -Dtest=DocEdgeRepositoryIntegrationTest test`
Expected: FAIL - `DocEdgeRepository` does not exist (compile error).

- [ ] **Step 3: Write minimal implementation**

```java
package com.example.springbootrag.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class DocEdgeRepository {

    private final JdbcTemplate jdbc;

    public DocEdgeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertLink(long projectId, String srcDoc, String dstDoc) {
        upsert(projectId, srcDoc, dstDoc, "link");
    }

    public void insertHierarchy(long projectId, String parentDoc, String childDoc) {
        upsert(projectId, parentDoc, childDoc, "hierarchy");
    }

    private void upsert(long projectId, String srcDoc, String dstDoc, String kind) {
        jdbc.update(
                "INSERT INTO doc_edge (project_id, src_doc, dst_doc, kind) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (project_id, src_doc, dst_doc, kind) DO NOTHING",
                projectId, srcDoc, dstDoc, kind);
    }

    /** Distinct dst_doc reachable in one hop from any of srcDocs (both kinds). */
    public List<String> neighbors(long projectId, List<String> srcDocs) {
        if (srcDocs == null || srcDocs.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(srcDocs.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        args.addAll(srcDocs);
        return jdbc.queryForList(
                "SELECT DISTINCT dst_doc FROM doc_edge " +
                "WHERE project_id = ? AND src_doc IN (" + placeholders + ")",
                String.class, args.toArray());
    }

    public void deleteBySrcDoc(long projectId, String srcDoc) {
        jdbc.update("DELETE FROM doc_edge WHERE project_id = ? AND src_doc = ?", projectId, srcDoc);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvnw.cmd -Dtest=DocEdgeRepositoryIntegrationTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/springbootrag/repository/DocEdgeRepository.java src/test/java/com/example/springbootrag/integration/DocEdgeRepositoryIntegrationTest.java
git commit -m "feat(graph): add DocEdgeRepository for structural edges"
```

---

### Task 4: GraphProperties config

**Files:**
- Create: `src/main/java/com/example/springbootrag/config/GraphProperties.java`
- Create: `src/main/java/com/example/springbootrag/config/GraphConfig.java`
- Modify: `src/main/resources/application.yml` (add `app.graph` block)
- Test: `src/test/java/com/example/springbootrag/config/GraphPropertiesTest.java`

**Interfaces:**
- Produces: `GraphProperties` with `boolean enabled` (default true), `String edges` (default `"structural"` for Phase 1; `structural|semantic|both`), `int neighborHops` (default 1), `int candidates` (default 50). Getters/setters. Registered via `@EnableConfigurationProperties(GraphProperties.class)` in `GraphConfig`.

- [ ] **Step 1: Write the failing test**

```java
package com.example.springbootrag.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class GraphPropertiesTest {

    @Autowired GraphProperties props;

    @Test
    void defaultsAreLoaded() {
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getEdges()).isEqualTo("structural");
        assertThat(props.getNeighborHops()).isEqualTo(1);
        assertThat(props.getCandidates()).isEqualTo(50);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvnw.cmd -Dtest=GraphPropertiesTest test`
Expected: FAIL - `GraphProperties` does not exist (compile error).

- [ ] **Step 3: Write the config classes and yml**

`GraphProperties.java`:

```java
package com.example.springbootrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.graph")
public class GraphProperties {
    private boolean enabled = true;
    /** structural | semantic | both. Phase 1 ships "structural". */
    private String edges = "structural";
    private int neighborHops = 1;
    /** How many candidates to gather before reranking to topK. */
    private int candidates = 50;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEdges() { return edges; }
    public void setEdges(String edges) { this.edges = edges; }
    public int getNeighborHops() { return neighborHops; }
    public void setNeighborHops(int neighborHops) { this.neighborHops = neighborHops; }
    public int getCandidates() { return candidates; }
    public void setCandidates(int candidates) { this.candidates = candidates; }
}
```

`GraphConfig.java`:

```java
package com.example.springbootrag.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GraphProperties.class)
public class GraphConfig {
}
```

Add to `application.yml` under `app:` (sibling of `rerank:`):

```yaml
  graph:
    enabled: true
    edges: structural      # structural | semantic | both  (semantic arrives in Phase 2)
    neighbor-hops: 1
    candidates: 50
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvnw.cmd -Dtest=GraphPropertiesTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/springbootrag/config/GraphProperties.java src/main/java/com/example/springbootrag/config/GraphConfig.java src/main/resources/application.yml src/test/java/com/example/springbootrag/config/GraphPropertiesTest.java
git commit -m "feat(graph): add app.graph configuration properties"
```

---

### Task 5: Add updatedAt to SearchHit and all row mappers

**Files:**
- Modify: `src/main/java/com/example/springbootrag/model/SearchHit.java`
- Modify: `src/main/java/com/example/springbootrag/repository/PgVectorRepository.java` (search mapper + insert)
- Modify: `src/main/java/com/example/springbootrag/repository/PgFtsRepository.java` (search mapper)
- Modify: `src/main/java/com/example/springbootrag/repository/QdrantRepository.java` (SearchHit construction)
- Test: `src/test/java/com/example/springbootrag/integration/UpdatedAtIntegrationTest.java`

**Interfaces:**
- Produces: `SearchHit` gains trailing field `java.time.Instant updatedAt` (nullable). `PgVectorRepository.insert(...)` gains a trailing `Instant updatedAt` parameter written to `chunks.updated_at`. All backend row mappers select `updated_at` and pass it through (Qdrant, which does not store it, passes `null`).

- [ ] **Step 1: Write the failing test**

```java
package com.example.springbootrag.integration;

import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.repository.PgVectorRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class UpdatedAtIntegrationTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }

    @Autowired PgVectorRepository repo;
    @Autowired JdbcTemplate jdbc;

    @Test
    void insertAndReadBackUpdatedAt() {
        long p = jdbc.queryForObject("SELECT id FROM projects ORDER BY id LIMIT 1", Long.class);
        Instant when = Instant.parse("2026-06-01T00:00:00Z");
        float[] vec = new float[768];
        repo.insert(p, "doc-recency", 0, "hello world", "doc-recency.md", null, vec, when);

        List<SearchHit> hits = repo.search(vec, 5, List.of(p), List.of("doc-recency"));
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).updatedAt()).isEqualTo(when);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvnw.cmd -Dtest=UpdatedAtIntegrationTest test`
Expected: FAIL - `insert(...)` has no `Instant` overload / `SearchHit.updatedAt()` does not exist (compile error).

- [ ] **Step 3: Update SearchHit**

```java
package com.example.springbootrag.model;

import java.time.Instant;

/** One search result row, shared by every backend. Metadata fields are null for pre-metadata rows. */
public record SearchHit(
        long id,
        String docId,
        int chunkIndex,
        String content,
        String sourceFile,
        String headingPath,
        double score,
        Instant updatedAt
) {}
```

- [ ] **Step 4: Update PgVectorRepository insert + search mapper**

Change `insert` signature and SQL to write `updated_at`:

```java
public long insert(long projectId, String docId, int chunkIndex, String content,
                   String sourceFile, String headingPath, float[] embedding, java.time.Instant updatedAt) {
    return jdbc.queryForObject(
            "INSERT INTO chunks (project_id, doc_id, chunk_index, content, source_file, heading_path, embedding, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?::vector, ?) RETURNING id",
            Long.class,
            projectId, docId, chunkIndex, content, sourceFile, headingPath,
            toVectorLiteral(embedding),
            updatedAt == null ? null : java.sql.Timestamp.from(updatedAt));
}
```

In the `search` method, add `updated_at` to the SELECT and mapper:

```java
return jdbc.query(
        "SELECT id, doc_id, chunk_index, content, source_file, heading_path, updated_at, " +
        "       embedding <=> ?::vector AS distance FROM chunks" + where +
        " ORDER BY distance ASC LIMIT ?",
        (rs, n) -> new SearchHit(
                rs.getLong("id"),
                rs.getString("doc_id"),
                rs.getInt("chunk_index"),
                rs.getString("content"),
                rs.getString("source_file"),
                rs.getString("heading_path"),
                1.0 - rs.getDouble("distance"),
                toInstant(rs.getTimestamp("updated_at"))),
        args.toArray());
```

Add a helper at the bottom of the class:

```java
static java.time.Instant toInstant(java.sql.Timestamp ts) {
    return ts == null ? null : ts.toInstant();
}
```

- [ ] **Step 5: Update PgFtsRepository mapper**

In `PgFtsRepository.search`, add `updated_at` to the SELECT list and pass `PgVectorRepository.toInstant(rs.getTimestamp("updated_at"))` as the final `SearchHit` argument (mirror the pattern above). If the mapper currently constructs `new SearchHit(...)` with 7 args, add the 8th.

- [ ] **Step 6: Update QdrantRepository SearchHit construction**

Qdrant does not persist `updated_at`; pass `null` as the trailing `SearchHit` argument wherever it builds a `SearchHit`.

- [ ] **Step 7: Update existing callers of insert**

`IngestService.ingestChunks` calls `pgVector.insert(...)` - add a trailing argument. For now pass the doc-level `updatedAt` (added in Task 6). To keep this task compiling standalone, temporarily pass `null`:

```java
long id = pgVector.insert(projectId, docId, chunk.position(), chunk.text(),
        sourceFile, chunk.headingPath(), vec, null);
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `mvnw.cmd -Dtest=UpdatedAtIntegrationTest test`
Expected: PASS.
Run: `mvnw.cmd test` (full suite compiles + green with the new 8-arg `SearchHit`).
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/example/springbootrag/model/SearchHit.java src/main/java/com/example/springbootrag/repository/PgVectorRepository.java src/main/java/com/example/springbootrag/repository/PgFtsRepository.java src/main/java/com/example/springbootrag/repository/QdrantRepository.java src/main/java/com/example/springbootrag/service/IngestService.java src/test/java/com/example/springbootrag/integration/UpdatedAtIntegrationTest.java
git commit -m "feat(graph): carry per-chunk updated_at through SearchHit and mappers"
```

---

### Task 6: Wire ingest - write doc_edge, capture updatedAt, cascade delete

**Files:**
- Modify: `src/main/java/com/example/springbootrag/service/IngestService.java`
- Modify: `src/main/java/com/example/springbootrag/web/DocumentController.java` (pass updatedAt through - default null/now)
- Test: `src/test/java/com/example/springbootrag/integration/GraphIngestIntegrationTest.java`

**Interfaces:**
- Consumes: `WikiLinkParser` (Task 2), `DocEdgeRepository` (Task 3), `PgVectorRepository.insert(..., Instant)` (Task 5).
- Produces:
  - `IngestService.ingestMarkdown(long projectId, String docId, String sourceFile, String markdownText, Instant updatedAt)` - full overload; existing 4-arg overload delegates with `updatedAt = null`.
  - Ingest now: parses links -> `DocEdgeRepository.insertLink` per outbound target; passes `updatedAt` to every chunk insert.
  - `IngestService.delete(long, String)` also calls `DocEdgeRepository.deleteBySrcDoc` (cascade).

- [ ] **Step 1: Write the failing test**

```java
package com.example.springbootrag.integration;

import com.example.springbootrag.repository.DocEdgeRepository;
import com.example.springbootrag.service.IngestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class GraphIngestIntegrationTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
        // Use the real embedding provider? No - integration tests use fake embeddings.
        // Rely on the existing test embedding config used by other *IntegrationTest classes.
    }

    @Autowired IngestService ingest;
    @Autowired DocEdgeRepository edges;
    @Autowired JdbcTemplate jdbc;

    private long projectId() {
        return jdbc.queryForObject("SELECT id FROM projects ORDER BY id LIMIT 1", Long.class);
    }

    @Test
    void ingestWritesLinkEdgesAndDeleteCascades() {
        long p = projectId();
        String md = "# Page A\n\nLinks to [B](/Page-B).";
        ingest.ingestMarkdown(p, "Page-A", "Page-A.md", md, Instant.parse("2026-06-01T00:00:00Z"));

        assertThat(edges.neighbors(p, List.of("Page-A"))).containsExactly("Page-B");

        ingest.delete(p, "Page-A");
        assertThat(edges.neighbors(p, List.of("Page-A"))).isEmpty();
    }
}
```

Note: this test needs the same fake-embedding wiring the other `*IntegrationTest` classes use. Before writing, open `DocumentIntegrationTest.java` and copy its embedding test-config setup (a `@TestConfiguration` or property that swaps `OllamaEmbeddingProvider` for a deterministic fake) into this test so no Ollama is required.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvnw.cmd -Dtest=GraphIngestIntegrationTest test`
Expected: FAIL - `ingestMarkdown` has no 5-arg overload (compile error).

- [ ] **Step 3: Add the WikiLinkParser + DocEdgeRepository as IngestService dependencies**

In `IngestService`, add fields and constructor params:

```java
private final DocEdgeRepository docEdges;
private final WikiLinkParser linkParser = new WikiLinkParser();
```

Add `DocEdgeRepository docEdges` to the constructor and assign it. (Update the constructor call sites - Spring wires it automatically.)

- [ ] **Step 4: Add the 5-arg ingestMarkdown overload and edge writing**

```java
/** Markdown file ingest with an explicit document updated_at (e.g. git commit date). */
public int ingestMarkdown(long projectId, String docId, String sourceFile,
                          String markdownText, java.time.Instant updatedAt) {
    int stored = ingestChunks(projectId, docId, sourceFile, markdown.chunk(markdownText), updatedAt);
    // Structural edges: one 'link' edge per outbound cross-page reference.
    for (String dst : linkParser.outboundDocIds(markdownText)) {
        docEdges.insertLink(projectId, docId, dst);
    }
    return stored;
}
```

Change the existing 4-arg `ingestMarkdown` to delegate:

```java
public int ingestMarkdown(long projectId, String docId, String sourceFile, String markdownText) {
    return ingestMarkdown(projectId, docId, sourceFile, markdownText, null);
}
```

- [ ] **Step 5: Thread updatedAt through ingestChunks and cascade delete**

Add an `Instant updatedAt` parameter to `ingestChunks` and pass it into `pgVector.insert(...)` (replacing the temporary `null` from Task 5). Keep the old `ingestChunks` 4-arg signature delegating with `null` if any caller needs it. In `delete(long projectId, String docId)`, add:

```java
docEdges.deleteBySrcDoc(projectId, docId);
```

(placed alongside the existing `pgVector.deleteByDocId` / `qdrant.deleteByDocId` cascade).

- [ ] **Step 6: Pass updatedAt from DocumentController**

The HTTP multipart upload has no git date, so the legacy/project upload endpoints pass `null` (defaults to "no recency"). Leave `DocumentController` calling the 4-arg `ingestMarkdown` - no change needed unless you want an `updatedAt` form field (out of scope). Confirm it still compiles.

- [ ] **Step 7: Run tests to verify they pass**

Run: `mvnw.cmd -Dtest=GraphIngestIntegrationTest test`
Expected: PASS.
Run: `mvnw.cmd test`
Expected: PASS (full suite).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/example/springbootrag/service/IngestService.java src/test/java/com/example/springbootrag/integration/GraphIngestIntegrationTest.java
git commit -m "feat(graph): write structural edges and updated_at on ingest, cascade on delete"
```

---

### Task 7: SearchService "graph" backend with recency tiebreak

**Files:**
- Modify: `src/main/java/com/example/springbootrag/service/SearchService.java`
- Test: `src/test/java/com/example/springbootrag/service/SearchServiceGraphTest.java`

**Interfaces:**
- Consumes: `DocEdgeRepository` (Task 3), `GraphProperties` (Task 4), existing `hybrid(...)`, `Reranker`, `PgVectorRepository`.
- Produces: new `case "graph"` in both `search(...)` and `compare(...)`. Algorithm: run `hybrid` for seed hits; collect their docIds; `docEdges.neighbors(...)` -> pull chunks of neighbor docs via `pgVector`; union seed + neighbor chunks (dedup by chunk id); apply recency tiebreak; rerank to `topK`. If seed hits empty, return `hybrid` result (fallback).

- [ ] **Step 1: Write the failing test**

```java
package com.example.springbootrag.service;

import com.example.springbootrag.config.GraphProperties;
import com.example.springbootrag.config.RerankProperties;
import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.repository.DocEdgeRepository;
import com.example.springbootrag.repository.PgFtsRepository;
import com.example.springbootrag.repository.PgVectorRepository;
import com.example.springbootrag.repository.QdrantRepository;
import com.example.springbootrag.rerank.IdentityReranker;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SearchServiceGraphTest {

    private SearchHit hit(long id, String doc, Instant updated) {
        return new SearchHit(id, doc, 0, "content " + id, doc + ".md", null, 0.9, updated);
    }

    @Test
    void graphExpandsToLinkedNeighborDocs() {
        EmbeddingProvider embed = mock(EmbeddingProvider.class);
        when(embed.embed(anyString())).thenReturn(new float[768]);

        PgFtsRepository fts = mock(PgFtsRepository.class);
        PgVectorRepository vec = mock(PgVectorRepository.class);
        QdrantRepository qdrant = mock(QdrantRepository.class);
        DocEdgeRepository edges = mock(DocEdgeRepository.class);

        // hybrid seed = one hit in doc A
        when(fts.search(anyString(), anyInt(), anyList(), anyList()))
                .thenReturn(List.of(hit(1, "A", null)));
        when(vec.search(any(float[].class), anyInt(), anyList(), anyList()))
                .thenReturn(List.of(hit(1, "A", null)));
        // A links to B
        when(edges.neighbors(anyLong(), eq(List.of("A")))).thenReturn(List.of("B"));
        // neighbor pull returns a chunk from B
        when(vec.chunksByDocIds(anyLong(), eq(List.of("B"))))
                .thenReturn(List.of(hit(2, "B", null)));

        GraphProperties gp = new GraphProperties();
        RerankProperties rp = new RerankProperties();

        SearchService svc = new SearchService(embed, fts, vec, qdrant,
                new IdentityReranker(), rp, edges, gp);

        List<SearchHit> out = svc.search("graph", "q", 10, List.of(1L), List.of());
        assertThat(out).extracting(SearchHit::docId).contains("A", "B");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvnw.cmd -Dtest=SearchServiceGraphTest test`
Expected: FAIL - `SearchService` constructor has no `DocEdgeRepository`/`GraphProperties` params, and `PgVectorRepository.chunksByDocIds` does not exist (compile error).

- [ ] **Step 3: Add PgVectorRepository.chunksByDocIds**

In `PgVectorRepository`, add a method that returns chunks for a set of docIds as `SearchHit`s (score 0.0 placeholder; the reranker rescoring replaces it):

```java
/** All chunks for the given docIds in a project, as SearchHits (score 0; rerank rescoring follows). */
public List<SearchHit> chunksByDocIds(long projectId, List<String> docIds) {
    if (docIds == null || docIds.isEmpty()) {
        return List.of();
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(docIds.size(), "?"));
    List<Object> args = new ArrayList<>();
    args.add(projectId);
    args.addAll(docIds);
    return jdbc.query(
            "SELECT id, doc_id, chunk_index, content, source_file, heading_path, updated_at " +
            "FROM chunks WHERE project_id = ? AND doc_id IN (" + placeholders + ")",
            (rs, n) -> new SearchHit(
                    rs.getLong("id"), rs.getString("doc_id"), rs.getInt("chunk_index"),
                    rs.getString("content"), rs.getString("source_file"), rs.getString("heading_path"),
                    0.0, toInstant(rs.getTimestamp("updated_at"))),
            args.toArray());
}
```

- [ ] **Step 4: Extend SearchService constructor and add the graph case**

Add fields + constructor params `DocEdgeRepository docEdges, GraphProperties graphProps`. Add the case to the `switch` in `search(...)` and an `out.put("graph", timed(...))` line in `compare(...)`:

```java
case "graph" -> graph(query, embeddings.embed(query), topK, projectIds, docIds);
```

Implement:

```java
private List<SearchHit> graph(String query, float[] queryEmbedding, int topK,
                              List<Long> projectIds, List<String> docIds) {
    List<SearchHit> seed = hybrid(query, queryEmbedding, graphProps.getCandidates(), projectIds, docIds);
    if (seed.isEmpty()) {
        return seed;   // fallback: nothing to expand from
    }
    long projectId = projectIds.isEmpty() ? 0L : projectIds.get(0);
    List<String> seedDocs = seed.stream().map(SearchHit::docId).distinct().toList();
    List<String> neighborDocs = graphProps.isEnabled()
            ? docEdges.neighbors(projectId, seedDocs) : List.of();

    // Union seed chunks with neighbor-doc chunks, dedup by chunk id.
    java.util.LinkedHashMap<Long, SearchHit> byId = new java.util.LinkedHashMap<>();
    for (SearchHit h : seed) byId.put(h.id(), h);
    if (!neighborDocs.isEmpty()) {
        for (SearchHit h : pgVector.chunksByDocIds(projectId, neighborDocs)) {
            byId.putIfAbsent(h.id(), h);
        }
    }
    List<SearchHit> candidates = new java.util.ArrayList<>(byId.values());
    // Recency tiebreak: newer updated_at first, nulls last (stable, non-destructive).
    candidates.sort(java.util.Comparator.comparing(
            SearchHit::updatedAt,
            java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
    return reranker.rerank(query, candidates, topK);
}
```

Note: `projectId = 0` when no project filter means neighbor expansion is skipped for cross-project queries in Phase 1 (documented limitation); seed hits still return.

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvnw.cmd -Dtest=SearchServiceGraphTest test`
Expected: PASS.
Run: `mvnw.cmd test`
Expected: PASS (full suite; existing `SearchServiceRerankTest` constructor call may need the two new args - update it to pass a mock `DocEdgeRepository` and `new GraphProperties()`).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/springbootrag/service/SearchService.java src/main/java/com/example/springbootrag/repository/PgVectorRepository.java src/test/java/com/example/springbootrag/service/SearchServiceGraphTest.java src/test/java/com/example/springbootrag/service/SearchServiceRerankTest.java
git commit -m "feat(graph): add graph retrieval backend with doc_edge expansion and recency tiebreak"
```

---

### Task 8: Expose graph in /search, /compare, UI, and eval

**Files:**
- Modify: `src/main/java/com/example/springbootrag/web/SearchController.java` (accept `type=graph` - likely already passes `type` through; confirm validation allows it)
- Modify: `src/main/resources/static/app.js` (add `graph` to the backend dropdown + compare columns)
- Modify: `src/test/java/com/example/springbootrag/eval/RetrievalEvalTest.java` (add `graph` to the evaluated backends)
- Test: `src/test/java/com/example/springbootrag/integration/SearchIntegrationTest.java` (add a `type=graph` assertion)

**Interfaces:**
- Consumes: `SearchService.search("graph", ...)` and `compare(...)` now returning a `graph` key.
- Produces: `/search?type=graph` works end-to-end; `/compare` includes a `graph` column; UI dropdown offers `graph`; eval reports a `graph` row.

- [ ] **Step 1: Write the failing integration assertion**

In `SearchIntegrationTest`, add (adapt to the file's existing setup/fixtures):

```java
@Test
void graphBackendReturnsHitsForKnownQuery() {
    // (reuse the class's existing ingest fixture + query helper)
    var hits = searchService.search("graph", EXISTING_FIXTURE_QUERY, 5,
            java.util.List.of(fixtureProjectId), java.util.List.of());
    org.assertj.core.api.Assertions.assertThat(hits).isNotEmpty();
}
```

- [ ] **Step 2: Run test to verify it fails (or passes if wiring already complete)**

Run: `mvnw.cmd -Dtest=SearchIntegrationTest test`
Expected: FAIL only if `SearchController` rejects `type=graph`. If `SearchService` already accepts it and the controller forwards `type` verbatim, this may already pass - then this task is pure surface wiring (UI + eval).

- [ ] **Step 3: Confirm/relax SearchController type validation**

Open `SearchController`. If it validates `type` against an allow-list, add `"graph"`. If it forwards `type` straight to `SearchService`, no change (the `switch` default throws for unknown types). Ensure `/compare` needs no change (it returns the whole map, which now contains `graph`).

- [ ] **Step 4: Add graph to the UI**

In `src/main/resources/static/app.js`, find where backend options are listed (the search dropdown and the compare table columns - search for `"hybrid"` or `"rerank"`). Add `"graph"` to both lists so the dropdown offers it and the compare table renders its column.

- [ ] **Step 5: Add graph to the eval harness**

In `RetrievalEvalTest`, find the list of backend types it iterates (e.g. `List.of("fts","pgvector","qdrant","hybrid","rerank")`) and append `"graph"`. This makes the eval print top-K recall / MRR / hit@1 for `graph` next to the others.

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvnw.cmd -Dtest=SearchIntegrationTest test`
Expected: PASS.
Run: `mvnw.cmd test`
Expected: PASS (full suite; eval tests remain gated behind `-Dgroups=eval` and are not run here).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/example/springbootrag/web/SearchController.java src/main/resources/static/app.js src/test/java/com/example/springbootrag/eval/RetrievalEvalTest.java src/test/java/com/example/springbootrag/integration/SearchIntegrationTest.java
git commit -m "feat(graph): expose graph backend in search, compare, UI, and eval"
```

---

### Task 9: WikiImporter - bulk dir-walk import with git dates

**Files:**
- Create: `src/main/java/com/example/springbootrag/tool/WikiImporter.java`
- Test: `src/test/java/com/example/springbootrag/tool/WikiImporterManualTest.java` (gated, like `DjlSpikeTest`)

**Interfaces:**
- Consumes: `IngestService.ingestMarkdown(projectId, docId, sourceFile, text, updatedAt)`, `ProjectService`, `DocEdgeRepository`.
- Produces: `WikiImporter.importDir(long projectId, Path wikiRoot)` - walks `*.md` (skipping `.git`, `.attachments`), computes `updatedAt` via `git log -1 --format=%cI -- <relpath>` (fallback file mtime), ingests each page, and writes `.order`/folder hierarchy edges via `DocEdgeRepository.insertHierarchy`. Returns the count of imported pages.

- [ ] **Step 1: Write the gated manual test**

```java
package com.example.springbootrag.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;

/**
 * Manual bulk import against a real local wiki clone. Gated: set WIKI_DIR to the clone path
 * and RUN_WIKI_IMPORT=true. Not part of the normal suite (needs a real corpus + Ollama).
 *   RUN_WIKI_IMPORT=true WIKI_DIR=/path/to/wiki ./mvnw -Dtest=WikiImporterManualTest test
 */
@EnabledIfEnvironmentVariable(named = "RUN_WIKI_IMPORT", matches = "true")
class WikiImporterManualTest {

    @Test
    void importsWikiDirectory() {
        String dir = System.getenv("WIKI_DIR");
        // Boot a minimal context or reuse an existing Spring test harness to obtain WikiImporter.
        // Assert importDir(...) returns > 0 pages and doc_edge is non-empty.
        // (Left as a manual smoke: the assertion below is the shape, wire to your context.)
        org.junit.jupiter.api.Assertions.assertNotNull(dir, "set WIKI_DIR");
    }
}
```

- [ ] **Step 2: Run test to verify it is skipped without the env var**

Run: `mvnw.cmd -Dtest=WikiImporterManualTest test`
Expected: SKIPPED (no `RUN_WIKI_IMPORT`), suite green.

- [ ] **Step 3: Implement WikiImporter**

```java
package com.example.springbootrag.tool;

import com.example.springbootrag.repository.DocEdgeRepository;
import com.example.springbootrag.service.IngestService;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

/** Bulk-imports a local Azure-wiki git clone into one project. Dev tool, not an HTTP endpoint. */
@Component
public class WikiImporter {

    private final IngestService ingest;
    private final DocEdgeRepository docEdges;

    public WikiImporter(IngestService ingest, DocEdgeRepository docEdges) {
        this.ingest = ingest;
        this.docEdges = docEdges;
    }

    public int importDir(long projectId, Path wikiRoot) throws Exception {
        int count = 0;
        try (Stream<Path> paths = Files.walk(wikiRoot)) {
            List<Path> pages = paths
                    .filter(p -> p.toString().endsWith(".md"))
                    .filter(p -> !p.toString().contains(File_separatorGit()))
                    .filter(p -> !p.toString().contains(".attachments"))
                    .toList();
            for (Path page : pages) {
                String text = Files.readString(page, StandardCharsets.UTF_8);
                String docId = docIdOf(page);
                Instant updated = gitDate(wikiRoot, wikiRoot.relativize(page).toString());
                ingest.ingestMarkdown(projectId, docId, page.getFileName().toString(), text, updated);
                // hierarchy edge: parent folder page -> this page
                Path parent = page.getParent();
                if (parent != null && !parent.equals(wikiRoot)) {
                    docEdges.insertHierarchy(projectId, docIdOf(parent), docId);
                }
                count++;
            }
        }
        return count;
    }

    private static String File_separatorGit() {
        return java.io.File.separator + ".git" + java.io.File.separator;
    }

    /* Last path segment, sanitized, like DocumentController/WikiLinkParser. */
    static String docIdOf(Path p) {
        String name = p.getFileName().toString();
        String base = name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
        return base.replaceAll("[^a-zA-Z0-9._-]", "-");
    }

    /* git commit date of the file; falls back to file mtime, then now(). */
    static Instant gitDate(Path repoRoot, String relPath) {
        try {
            Process proc = new ProcessBuilder(
                    "git", "log", "-1", "--format=%cI", "--", relPath)
                    .directory(repoRoot.toFile())
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line = r.readLine();
                proc.waitFor();
                if (line != null && !line.isBlank()) {
                    return Instant.parse(line.trim());
                }
            }
        } catch (Exception ignored) {
            // fall through to mtime
        }
        try {
            return Files.getLastModifiedTime(repoRoot.resolve(relPath)).toInstant();
        } catch (Exception e) {
            return Instant.now();
        }
    }
}
```

- [ ] **Step 4: Run the gated smoke manually (optional, requires a real clone + Ollama)**

Run: `set RUN_WIKI_IMPORT=true && set WIKI_DIR=<clone path> && mvnw.cmd -Dtest=WikiImporterManualTest test`
Expected: import returns > 0 pages; `doc_edge` populated. (This is a manual smoke; keep it out of CI.)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/springbootrag/tool/WikiImporter.java src/test/java/com/example/springbootrag/tool/WikiImporterManualTest.java
git commit -m "feat(graph): add WikiImporter for bulk clone import with git-date recency"
```

---

## Self-Review

**Spec coverage (against `2026-07-04-graphrag-wiki-retrieval-design.md`):**
- 3a structural edges (links + hierarchy) -> Tasks 2, 3, 6, 9. ✅
- 4 data model `doc_edge` + recency -> Tasks 1, 5. (`entity`/`chunk_entity`/`entity_edge` are Phase 2, not this plan.) ✅
- 5 ingest flow (structural + updated_at + cascade delete) -> Tasks 6, 9. ✅
- 6 retrieval graph backend + fallback -> Task 7. ✅
- 6b recency tiebreak + SearchHit exposure -> Tasks 5, 7. ✅
- 7 config (`app.graph.*`) -> Task 4. (`extract-model`, `min-mentions` are Phase 2.) ✅
- 8 testing (unit link parser, integration cascade, eval column) -> Tasks 2, 6, 8. Orphan-reconnection test is Phase 2 (needs entities). ✅
- 9 non-goals - respected (no entities, no communities, no code ingestion). ✅

**Deferred to Phase 2 (semantic plan):** entity extraction, `entity`/`chunk_entity`/`entity_edge` tables, query-entity retrieval, orphan-reconnection test, `edges=semantic|both` behavior, `min-mentions`.

**Placeholder scan:** no TBD/TODO; every code step shows real code. The two gated/manual tests (`WikiImporterManualTest`) intentionally leave context wiring to the implementer because they run against a real corpus - this is called out, not a hidden gap.

**Type consistency:** `SearchHit` is 8-arg everywhere after Task 5 (Tasks 6-9 use the 8-arg form). `insert(...)` is 8-arg after Task 5. `neighbors`, `chunksByDocIds`, `insertLink`, `insertHierarchy`, `deleteBySrcDoc`, `ingestMarkdown(...,Instant)` signatures match across tasks. `SearchService` constructor gains exactly `(DocEdgeRepository, GraphProperties)` and every test constructing it (Task 7 + updated `SearchServiceRerankTest`) passes both.
