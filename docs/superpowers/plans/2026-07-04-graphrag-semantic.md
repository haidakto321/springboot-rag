# GraphRAG Semantic Layer Implementation Plan (Phase 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Prerequisite:** Phase 1 (`2026-07-04-graphrag-structural.md`) is merged - `doc_edge`, `GraphProperties`, the `graph` backend, and `SearchHit.updatedAt` already exist.

**Goal:** Add an LLM entity layer so the `graph` backend can reconnect orphan pages (no inbound links) through shared entities - the core "find a feature nobody remembers" use case.

**Architecture:** At ingest, when `app.graph.edges` is `semantic` or `both`, qwen3 (via the existing `ChatProvider`) extracts entities + relations per chunk into `entity`, `chunk_entity`, `entity_edge`. At query time the same extractor pulls query entities, matches them to stored entities, expands one hop over `entity_edge`, and pulls those entities' chunks - unioned with the Phase 1 structural + hybrid candidates, then reranked. A `min-mentions` noise floor drops one-off entities.

**Tech Stack:** Java 21, Spring Boot 3.5.6, plain `JdbcTemplate`, PostgreSQL, `ChatProvider` (Ollama qwen3), Jackson (already on the classpath via Spring Boot). JUnit 5 + Testcontainers + Mockito. No new dependencies.

## Global Constraints

- All Phase 1 Global Constraints still apply (idempotent `schema.sql`, plain `JdbcTemplate`, no new deps, conventional commits without `Co-Authored-By`, graph never returns empty when hybrid would not).
- Entity extraction is the slow/costly path: it runs ONLY when `app.graph.edges` is `semantic` or `both`. `structural` (Phase 1 default) must behave exactly as before.
- LLM extraction is best-effort: any parse failure or model error on a chunk is logged and skipped - it must NEVER fail the ingest of that document.
- Entity names are normalized to `lower(trim(name))` for matching (`name_norm`); the original surface form is kept in `name_display`. No coreference/fuzzy resolution in this phase.

---

## File Structure

- `src/main/resources/schema.sql` - add `entity`, `chunk_entity`, `entity_edge` (modify)
- `src/main/java/.../graph/EntityExtractor.java` - chunk text -> entities + relations via `ChatProvider` (create)
- `src/main/java/.../graph/ExtractedGraph.java` - record of extractor output (create)
- `src/main/java/.../repository/EntityRepository.java` - entity/chunk_entity/entity_edge CRUD + queries (create)
- `src/main/java/.../config/GraphProperties.java` - add `extractModel`, `minMentions` (modify)
- `src/main/java/.../service/IngestService.java` - semantic extraction on ingest + cascade delete (modify)
- `src/main/java/.../service/SearchService.java` - semantic expansion in `graph(...)` (modify)
- `src/main/resources/application.yml` - `edges: both`, `extract-model`, `min-mentions` (modify)
- Tests mirror each under `src/test/java/...`

---

### Task 1: Schema - entity, chunk_entity, entity_edge

**Files:**
- Modify: `src/main/resources/schema.sql`
- Test: `src/test/java/com/example/springbootrag/integration/EntitySchemaIntegrationTest.java`

**Interfaces:**
- Produces: `entity(id, project_id, name_norm, name_display, type, mention_count, created_at)` unique `(project_id, name_norm)`; `chunk_entity(chunk_id, entity_id)` PK both; `entity_edge(id, project_id, src_entity, dst_entity, relation, weight)` unique `(project_id, src_entity, dst_entity, relation)`.

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
class EntitySchemaIntegrationTest {

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
    void entityTablesExist() {
        for (String t : new String[]{"entity", "chunk_entity", "entity_edge"}) {
            Integer c = jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name = ?",
                    Integer.class, t);
            assertThat(c).as(t).isEqualTo(1);
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvnw.cmd -Dtest=EntitySchemaIntegrationTest test`
Expected: FAIL - tables missing.

- [ ] **Step 3: Add DDL to schema.sql**

```sql
-- ---- GraphRAG semantic layer (Phase 2) ----

CREATE TABLE IF NOT EXISTS entity (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id    BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name_norm     TEXT NOT NULL,
    name_display  TEXT NOT NULL,
    type          VARCHAR(64),
    mention_count INT NOT NULL DEFAULT 0,
    created_at    TIMESTAMP DEFAULT now(),
    UNIQUE (project_id, name_norm)
);

CREATE TABLE IF NOT EXISTS chunk_entity (
    chunk_id  BIGINT NOT NULL REFERENCES chunks(id) ON DELETE CASCADE,
    entity_id BIGINT NOT NULL REFERENCES entity(id) ON DELETE CASCADE,
    PRIMARY KEY (chunk_id, entity_id)
);

CREATE TABLE IF NOT EXISTS entity_edge (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    src_entity BIGINT NOT NULL REFERENCES entity(id) ON DELETE CASCADE,
    dst_entity BIGINT NOT NULL REFERENCES entity(id) ON DELETE CASCADE,
    relation   VARCHAR(128) NOT NULL,
    weight     DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    UNIQUE (project_id, src_entity, dst_entity, relation)
);

CREATE INDEX IF NOT EXISTS idx_chunk_entity_entity ON chunk_entity (entity_id);
CREATE INDEX IF NOT EXISTS idx_entity_edge_src ON entity_edge (project_id, src_entity);
CREATE INDEX IF NOT EXISTS idx_entity_name ON entity (project_id, name_norm);
```

Note: `chunk_entity`/`entity` FK cascade from `chunks` means deleting a chunk auto-clears its entity links - but orphaned `entity` rows (no remaining `chunk_entity`) still need explicit GC (Task 3/4).

- [ ] **Step 4: Run test to verify it passes**

Run: `mvnw.cmd -Dtest=EntitySchemaIntegrationTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/schema.sql src/test/java/com/example/springbootrag/integration/EntitySchemaIntegrationTest.java
git commit -m "feat(graph): add entity, chunk_entity, entity_edge tables"
```

---

### Task 2: EntityExtractor - LLM chunk -> entities + relations

**Files:**
- Create: `src/main/java/com/example/springbootrag/graph/ExtractedGraph.java`
- Create: `src/main/java/com/example/springbootrag/graph/EntityExtractor.java`
- Test: `src/test/java/com/example/springbootrag/graph/EntityExtractorTest.java`

**Interfaces:**
- Consumes: `ChatProvider.chat(String system, String user)` (returns raw model text).
- Produces:
  - `record ExtractedGraph(List<Entity> entities, List<Relation> relations)` with `record Entity(String name, String type)` and `record Relation(String src, String rel, String dst)`.
  - `class EntityExtractor { ExtractedGraph extract(String chunkText) }` - prompts the model for strict JSON, parses it, and returns an empty `ExtractedGraph` on any failure (best-effort).

- [ ] **Step 1: Write the failing test (mocked ChatProvider)**

```java
package com.example.springbootrag.graph;

import com.example.springbootrag.chat.ChatProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EntityExtractorTest {

    @Test
    void parsesEntitiesAndRelationsFromModelJson() {
        ChatProvider chat = mock(ChatProvider.class);
        when(chat.chat(anyString(), anyString())).thenReturn("""
            {"entities":[{"name":"PaymentsService","type":"service"},
                         {"name":"Alice","type":"team"}],
             "relations":[{"src":"Alice","rel":"owns","dst":"PaymentsService"}]}
            """);

        EntityExtractor ex = new EntityExtractor(chat, "");
        ExtractedGraph g = ex.extract("Alice owns the PaymentsService.");

        assertThat(g.entities()).extracting(ExtractedGraph.Entity::name)
                .containsExactlyInAnyOrder("PaymentsService", "Alice");
        assertThat(g.relations()).hasSize(1);
        assertThat(g.relations().get(0).rel()).isEqualTo("owns");
    }

    @Test
    void returnsEmptyOnGarbageModelOutput() {
        ChatProvider chat = mock(ChatProvider.class);
        when(chat.chat(anyString(), anyString())).thenReturn("sorry I cannot do that");

        EntityExtractor ex = new EntityExtractor(chat, "");
        ExtractedGraph g = ex.extract("whatever");

        assertThat(g.entities()).isEmpty();
        assertThat(g.relations()).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvnw.cmd -Dtest=EntityExtractorTest test`
Expected: FAIL - classes do not exist (compile error).

- [ ] **Step 3: Write ExtractedGraph**

```java
package com.example.springbootrag.graph;

import java.util.List;

public record ExtractedGraph(List<Entity> entities, List<Relation> relations) {
    public record Entity(String name, String type) {}
    public record Relation(String src, String rel, String dst) {}

    public static ExtractedGraph empty() {
        return new ExtractedGraph(List.of(), List.of());
    }
}
```

- [ ] **Step 4: Write EntityExtractor**

```java
package com.example.springbootrag.graph;

import com.example.springbootrag.chat.ChatProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts entities + relations from a chunk via the chat model. Best-effort:
 * returns ExtractedGraph.empty() on any model or parse failure so ingest never breaks.
 */
public class EntityExtractor {

    static final String SYSTEM = """
            Extract named entities and their relations from the text. Entity types are hints:
            service, feature, team, concept - use "other" if none fit. Respond with ONLY a JSON
            object, no prose, in this exact shape:
            {"entities":[{"name":"...","type":"..."}],
             "relations":[{"src":"...","rel":"...","dst":"..."}]}
            If there are no entities, return {"entities":[],"relations":[]}.""";

    private final ChatProvider chat;
    private final String model;   // reserved: a dedicated extract model; blank = provider default
    private final ObjectMapper mapper = new ObjectMapper();

    public EntityExtractor(ChatProvider chat, String model) {
        this.chat = chat;
        this.model = model;
    }

    public ExtractedGraph extract(String chunkText) {
        try {
            String raw = chat.chat(SYSTEM, chunkText);
            String json = sliceJson(raw);
            if (json == null) return ExtractedGraph.empty();
            JsonNode root = mapper.readTree(json);
            return new ExtractedGraph(readEntities(root.get("entities")),
                                      readRelations(root.get("relations")));
        } catch (Exception e) {
            return ExtractedGraph.empty();
        }
    }

    /* Extract the first {...} block so stray tokens around the JSON do not break parsing. */
    static String sliceJson(String raw) {
        if (raw == null) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : null;
    }

    private static List<ExtractedGraph.Entity> readEntities(JsonNode arr) {
        List<ExtractedGraph.Entity> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                String name = text(n, "name");
                if (name != null && !name.isBlank()) {
                    out.add(new ExtractedGraph.Entity(name.trim(), orOther(text(n, "type"))));
                }
            }
        }
        return out;
    }

    private static List<ExtractedGraph.Relation> readRelations(JsonNode arr) {
        List<ExtractedGraph.Relation> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                String src = text(n, "src"), rel = text(n, "rel"), dst = text(n, "dst");
                if (src != null && dst != null && rel != null) {
                    out.add(new ExtractedGraph.Relation(src.trim(), rel.trim(), dst.trim()));
                }
            }
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String orOther(String type) {
        return type == null || type.isBlank() ? "other" : type.trim();
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvnw.cmd -Dtest=EntityExtractorTest test`
Expected: PASS (both tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/springbootrag/graph/ExtractedGraph.java src/main/java/com/example/springbootrag/graph/EntityExtractor.java src/test/java/com/example/springbootrag/graph/EntityExtractorTest.java
git commit -m "feat(graph): add LLM entity extractor with best-effort JSON parsing"
```

---

### Task 3: EntityRepository - persist entities and query the semantic graph

**Files:**
- Create: `src/main/java/com/example/springbootrag/repository/EntityRepository.java`
- Test: `src/test/java/com/example/springbootrag/integration/EntityRepositoryIntegrationTest.java`

**Interfaces:**
- Produces:
  - `long upsertEntity(long projectId, String nameDisplay, String type)` - insert or bump `mention_count`; returns entity id (matched by `name_norm = lower(trim(name))`).
  - `void linkChunk(long chunkId, long entityId)` - insert into `chunk_entity` (ON CONFLICT DO NOTHING).
  - `void insertEdge(long projectId, long srcEntity, long dstEntity, String relation)` - upsert `entity_edge`.
  - `List<Long> matchEntityIds(long projectId, List<String> names, int minMentions)` - entity ids whose `name_norm` is in the normalized names and `mention_count >= minMentions`.
  - `List<Long> neighborEntityIds(long projectId, List<Long> entityIds)` - one-hop `entity_edge` neighbors.
  - `List<Long> chunkIdsForEntities(List<Long> entityIds)` - distinct chunk ids linked to any entity.
  - `void gcOrphanEntities(long projectId)` - delete `entity` rows with no `chunk_entity` (called after doc delete).

- [ ] **Step 1: Write the failing test**

```java
package com.example.springbootrag.integration;

import com.example.springbootrag.repository.EntityRepository;
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
class EntityRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }

    @Autowired EntityRepository repo;
    @Autowired JdbcTemplate jdbc;

    private long projectId() {
        return jdbc.queryForObject("SELECT id FROM projects ORDER BY id LIMIT 1", Long.class);
    }

    @Test
    void upsertMatchNeighborAndGc() {
        long p = projectId();
        long alice = repo.upsertEntity(p, "Alice", "team");
        long svc = repo.upsertEntity(p, "PaymentsService", "service");
        long sameAlice = repo.upsertEntity(p, "  alice ", "team");   // normalized dup
        assertThat(sameAlice).isEqualTo(alice);

        repo.insertEdge(p, alice, svc, "owns");
        assertThat(repo.matchEntityIds(p, List.of("Alice"), 1)).containsExactly(alice);
        assertThat(repo.neighborEntityIds(p, List.of(alice))).containsExactly(svc);

        // no chunk links -> both are orphans -> gc removes them
        repo.gcOrphanEntities(p);
        assertThat(repo.matchEntityIds(p, List.of("Alice"), 1)).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvnw.cmd -Dtest=EntityRepositoryIntegrationTest test`
Expected: FAIL - `EntityRepository` does not exist (compile error).

- [ ] **Step 3: Write minimal implementation**

```java
package com.example.springbootrag.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Repository
public class EntityRepository {

    private final JdbcTemplate jdbc;

    public EntityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long upsertEntity(long projectId, String nameDisplay, String type) {
        String norm = nameDisplay.trim().toLowerCase();
        return jdbc.queryForObject(
                "INSERT INTO entity (project_id, name_norm, name_display, type, mention_count) " +
                "VALUES (?, ?, ?, ?, 1) " +
                "ON CONFLICT (project_id, name_norm) DO UPDATE SET mention_count = entity.mention_count + 1 " +
                "RETURNING id",
                Long.class, projectId, norm, nameDisplay.trim(), type);
    }

    public void linkChunk(long chunkId, long entityId) {
        jdbc.update("INSERT INTO chunk_entity (chunk_id, entity_id) VALUES (?, ?) " +
                "ON CONFLICT DO NOTHING", chunkId, entityId);
    }

    public void insertEdge(long projectId, long srcEntity, long dstEntity, String relation) {
        jdbc.update(
                "INSERT INTO entity_edge (project_id, src_entity, dst_entity, relation) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (project_id, src_entity, dst_entity, relation) DO NOTHING",
                projectId, srcEntity, dstEntity, relation);
    }

    public List<Long> matchEntityIds(long projectId, List<String> names, int minMentions) {
        if (names == null || names.isEmpty()) return List.of();
        List<String> norm = names.stream().map(s -> s.trim().toLowerCase()).toList();
        String ph = String.join(",", Collections.nCopies(norm.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        args.addAll(norm);
        args.add(minMentions);
        return jdbc.queryForList(
                "SELECT id FROM entity WHERE project_id = ? AND name_norm IN (" + ph + ") " +
                "AND mention_count >= ?",
                Long.class, args.toArray());
    }

    public List<Long> neighborEntityIds(long projectId, List<Long> entityIds) {
        if (entityIds == null || entityIds.isEmpty()) return List.of();
        String ph = String.join(",", Collections.nCopies(entityIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        args.addAll(entityIds);
        return jdbc.queryForList(
                "SELECT DISTINCT dst_entity FROM entity_edge " +
                "WHERE project_id = ? AND src_entity IN (" + ph + ")",
                Long.class, args.toArray());
    }

    public List<Long> chunkIdsForEntities(List<Long> entityIds) {
        if (entityIds == null || entityIds.isEmpty()) return List.of();
        String ph = String.join(",", Collections.nCopies(entityIds.size(), "?"));
        return jdbc.queryForList(
                "SELECT DISTINCT chunk_id FROM chunk_entity WHERE entity_id IN (" + ph + ")",
                Long.class, entityIds.toArray());
    }

    public void gcOrphanEntities(long projectId) {
        jdbc.update(
                "DELETE FROM entity WHERE project_id = ? AND id NOT IN " +
                "(SELECT DISTINCT entity_id FROM chunk_entity)",
                projectId);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvnw.cmd -Dtest=EntityRepositoryIntegrationTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/springbootrag/repository/EntityRepository.java src/test/java/com/example/springbootrag/integration/EntityRepositoryIntegrationTest.java
git commit -m "feat(graph): add EntityRepository for the semantic graph"
```

---

### Task 4: Config additions + wire semantic extraction into ingest

**Files:**
- Modify: `src/main/java/com/example/springbootrag/config/GraphProperties.java` (add `extractModel`, `minMentions`)
- Modify: `src/main/resources/application.yml` (`edges: both`, `extract-model`, `min-mentions`)
- Modify: `src/main/java/com/example/springbootrag/service/IngestService.java` (extract when edges include semantic; cascade GC on delete)
- Create: `src/main/java/com/example/springbootrag/config/EntityExtractorConfig.java` (bean wiring `EntityExtractor`)
- Test: `src/test/java/com/example/springbootrag/integration/SemanticIngestIntegrationTest.java`

**Interfaces:**
- Consumes: `EntityExtractor` (Task 2), `EntityRepository` (Task 3), `GraphProperties` (Phase 1 + this task).
- Produces:
  - `GraphProperties.getExtractModel()` (default `""`), `getMinMentions()` (default 1).
  - `@Bean EntityExtractor entityExtractor(ChatProvider chat, GraphProperties props)` using `props.getExtractModel()`.
  - `IngestService.ingestChunks(...)`: after inserting each chunk, if `edges` is `semantic|both`, call the extractor and persist entities/links/edges keyed on that chunk id.
  - `IngestService.delete(...)`: after deleting chunks (which cascades `chunk_entity`), call `entityRepo.gcOrphanEntities(projectId)`.

- [ ] **Step 1: Write the failing test (mocked extractor via a stub ChatProvider)**

```java
package com.example.springbootrag.integration;

import com.example.springbootrag.repository.EntityRepository;
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

@SpringBootTest(properties = "app.graph.edges=both")
@Testcontainers
class SemanticIngestIntegrationTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }

    @Autowired IngestService ingest;
    @Autowired EntityRepository entities;
    @Autowired JdbcTemplate jdbc;

    private long projectId() {
        return jdbc.queryForObject("SELECT id FROM projects ORDER BY id LIMIT 1", Long.class);
    }

    @Test
    void ingestExtractsEntitiesAndDeleteGcsThem() {
        long p = projectId();
        ingest.ingestMarkdown(p, "Feature-X", "Feature-X.md",
                "# Feature X\n\nAlice owns the PaymentsService.", Instant.now());

        // A deterministic fake ChatProvider (see step 3) yields at least one entity.
        assertThat(entities.matchEntityIds(p, List.of("PaymentsService"), 1)).isNotEmpty();

        ingest.delete(p, "Feature-X");
        assertThat(entities.matchEntityIds(p, List.of("PaymentsService"), 1)).isEmpty();
    }
}
```

This test needs a deterministic `ChatProvider` bean (no real Ollama). Add a `@TestConfiguration` in the test that provides a `ChatProvider` whose `chat(system, user)` returns a fixed JSON `{"entities":[{"name":"PaymentsService","type":"service"},{"name":"Alice","type":"team"}],"relations":[{"src":"Alice","rel":"owns","dst":"PaymentsService"}]}`. Follow the fake-embedding pattern the other `*IntegrationTest` classes use for swapping providers.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvnw.cmd -Dtest=SemanticIngestIntegrationTest test`
Expected: FAIL - extraction not wired; `matchEntityIds` empty (or compile error on new config getters).

- [ ] **Step 3: Add GraphProperties fields**

```java
/** Blank = reuse the chat provider's default model. */
private String extractModel = "";
/** Entities mentioned fewer than this are ignored at query match time. */
private int minMentions = 1;

public String getExtractModel() { return extractModel; }
public void setExtractModel(String extractModel) { this.extractModel = extractModel; }
public int getMinMentions() { return minMentions; }
public void setMinMentions(int minMentions) { this.minMentions = minMentions; }
```

Update `application.yml`:

```yaml
  graph:
    enabled: true
    edges: both            # structural | semantic | both
    neighbor-hops: 1
    candidates: 50
    extract-model: ""      # blank = reuse app.chat.model
    min-mentions: 1
```

- [ ] **Step 4: Add the EntityExtractor bean**

```java
package com.example.springbootrag.config;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.graph.EntityExtractor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EntityExtractorConfig {

    @Bean
    public EntityExtractor entityExtractor(ChatProvider chat, GraphProperties props) {
        return new EntityExtractor(chat, props.getExtractModel());
    }
}
```

- [ ] **Step 5: Wire extraction into IngestService.ingestChunks**

Add `EntityExtractor`, `EntityRepository`, `GraphProperties` as constructor deps. After each `pgVector.insert(...)` returns the chunk `id`, when semantic extraction is enabled, persist:

```java
if (semanticEnabled()) {
    extractAndPersist(projectId, id, chunk.text());
}
```

Helpers:

```java
private boolean semanticEnabled() {
    String e = graphProps.getEdges();
    return "semantic".equalsIgnoreCase(e) || "both".equalsIgnoreCase(e);
}

private void extractAndPersist(long projectId, long chunkId, String text) {
    ExtractedGraph g = entityExtractor.extract(text);
    java.util.Map<String, Long> ids = new java.util.HashMap<>();
    for (ExtractedGraph.Entity e : g.entities()) {
        long eid = entityRepo.upsertEntity(projectId, e.name(), e.type());
        entityRepo.linkChunk(chunkId, eid);
        ids.put(e.name().trim().toLowerCase(), eid);
    }
    for (ExtractedGraph.Relation r : g.relations()) {
        Long s = ids.get(r.src().trim().toLowerCase());
        Long d = ids.get(r.dst().trim().toLowerCase());
        if (s != null && d != null) {
            entityRepo.insertEdge(projectId, s, d, r.rel());
        }
    }
}
```

In `delete(long projectId, String docId)`, after the existing chunk deletes (which cascade `chunk_entity`), add:

```java
entityRepo.gcOrphanEntities(projectId);
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvnw.cmd -Dtest=SemanticIngestIntegrationTest test`
Expected: PASS.
Run: `mvnw.cmd test`
Expected: PASS (structural-only tests still green because extraction is gated on `edges`).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/example/springbootrag/config/GraphProperties.java src/main/java/com/example/springbootrag/config/EntityExtractorConfig.java src/main/resources/application.yml src/main/java/com/example/springbootrag/service/IngestService.java src/test/java/com/example/springbootrag/integration/SemanticIngestIntegrationTest.java
git commit -m "feat(graph): extract and persist entities on ingest, gc orphans on delete"
```

---

### Task 5: Semantic expansion in the graph backend

**Files:**
- Modify: `src/main/java/com/example/springbootrag/service/SearchService.java`
- Modify: `src/main/java/com/example/springbootrag/repository/PgVectorRepository.java` (add `chunksByIds`)
- Test: `src/test/java/com/example/springbootrag/service/SearchServiceSemanticTest.java`

**Interfaces:**
- Consumes: `EntityExtractor`, `EntityRepository`, `PgVectorRepository.chunksByIds(List<Long>)`, `GraphProperties.getMinMentions()`.
- Produces: `graph(...)` additionally, when `edges` is `semantic|both`: extract query entities -> `matchEntityIds` -> `neighborEntityIds` (1 hop) -> `chunkIdsForEntities` -> `chunksByIds` -> unioned into the candidate set before rerank. Structural expansion from Phase 1 still runs when `edges` is `structural|both`.

- [ ] **Step 1: Add PgVectorRepository.chunksByIds**

```java
/** Chunks for the given ids, as SearchHits (score 0; rerank rescoring follows). */
public List<SearchHit> chunksByIds(List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
        return List.of();
    }
    String ph = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
    return jdbc.query(
            "SELECT id, doc_id, chunk_index, content, source_file, heading_path, updated_at " +
            "FROM chunks WHERE id IN (" + ph + ")",
            (rs, n) -> new SearchHit(
                    rs.getLong("id"), rs.getString("doc_id"), rs.getInt("chunk_index"),
                    rs.getString("content"), rs.getString("source_file"), rs.getString("heading_path"),
                    0.0, toInstant(rs.getTimestamp("updated_at"))),
            ids.toArray());
}
```

- [ ] **Step 2: Write the failing test**

```java
package com.example.springbootrag.service;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.config.GraphProperties;
import com.example.springbootrag.config.RerankProperties;
import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.graph.EntityExtractor;
import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.repository.DocEdgeRepository;
import com.example.springbootrag.repository.EntityRepository;
import com.example.springbootrag.repository.PgFtsRepository;
import com.example.springbootrag.repository.PgVectorRepository;
import com.example.springbootrag.repository.QdrantRepository;
import com.example.springbootrag.rerank.IdentityReranker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SearchServiceSemanticTest {

    private SearchHit hit(long id, String doc) {
        return new SearchHit(id, doc, 0, "c" + id, doc + ".md", null, 0.5, null);
    }

    @Test
    void semanticExpansionPullsOrphanChunkViaSharedEntity() {
        EmbeddingProvider embed = mock(EmbeddingProvider.class);
        when(embed.embed(anyString())).thenReturn(new float[768]);
        PgFtsRepository fts = mock(PgFtsRepository.class);
        PgVectorRepository vec = mock(PgVectorRepository.class);
        QdrantRepository qdrant = mock(QdrantRepository.class);
        DocEdgeRepository edges = mock(DocEdgeRepository.class);
        EntityRepository entities = mock(EntityRepository.class);
        ChatProvider chat = mock(ChatProvider.class);

        // seed hybrid = a chunk in the well-known doc A
        when(fts.search(anyString(), anyInt(), anyList(), anyList())).thenReturn(List.of(hit(1, "A")));
        when(vec.search(any(float[].class), anyInt(), anyList(), anyList())).thenReturn(List.of(hit(1, "A")));
        when(edges.neighbors(anyLong(), anyList())).thenReturn(List.of());   // no structural link (orphan)

        // query mentions PaymentsService -> matches entity 10 -> chunk 99 lives in orphan doc B
        when(chat.chat(anyString(), anyString()))
                .thenReturn("{\"entities\":[{\"name\":\"PaymentsService\",\"type\":\"service\"}],\"relations\":[]}");
        when(entities.matchEntityIds(anyLong(), anyList(), anyInt())).thenReturn(List.of(10L));
        when(entities.neighborEntityIds(anyLong(), eq(List.of(10L)))).thenReturn(List.of());
        when(entities.chunkIdsForEntities(anyList())).thenReturn(List.of(99L));
        when(vec.chunksByIds(eq(List.of(99L)))).thenReturn(List.of(hit(99, "B")));

        GraphProperties gp = new GraphProperties();
        gp.setEdges("both");
        SearchService svc = new SearchService(embed, fts, vec, qdrant, new IdentityReranker(),
                new RerankProperties(), edges, gp,
                new EntityExtractor(chat, ""), entities);

        List<SearchHit> out = svc.search("graph", "who owns PaymentsService", 10, List.of(1L), List.of());
        assertThat(out).extracting(SearchHit::docId).contains("A", "B");   // orphan B reconnected
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvnw.cmd -Dtest=SearchServiceSemanticTest test`
Expected: FAIL - `SearchService` constructor lacks `EntityExtractor`/`EntityRepository`; `chunksByIds` may be new (compile error).

- [ ] **Step 4: Extend SearchService**

Add constructor params `EntityExtractor entityExtractor, EntityRepository entityRepo` (after the Phase 1 `DocEdgeRepository docEdges, GraphProperties graphProps`). In `graph(...)`, after building the structural candidate map, add semantic expansion:

```java
if (semanticOn()) {
    ExtractedGraph qg = entityExtractor.extract(query);
    List<String> qNames = qg.entities().stream()
            .map(ExtractedGraph.Entity::name).toList();
    List<Long> matched = entityRepo.matchEntityIds(projectId, qNames, graphProps.getMinMentions());
    if (!matched.isEmpty()) {
        List<Long> expanded = new java.util.ArrayList<>(matched);
        expanded.addAll(entityRepo.neighborEntityIds(projectId, matched));
        List<Long> chunkIds = entityRepo.chunkIdsForEntities(expanded);
        for (SearchHit h : pgVector.chunksByIds(chunkIds)) {
            byId.putIfAbsent(h.id(), h);
        }
    }
}
```

with:

```java
private boolean semanticOn() {
    String e = graphProps.getEdges();
    return "semantic".equalsIgnoreCase(e) || "both".equalsIgnoreCase(e);
}
```

Gate the Phase 1 structural expansion behind a matching `structuralOn()` (`structural|both`) so `edges=semantic` skips `doc_edge`. Keep the recency sort + rerank at the end unchanged.

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvnw.cmd -Dtest=SearchServiceSemanticTest test`
Expected: PASS.
Run: `mvnw.cmd test`
Expected: PASS (update the Phase 1 `SearchServiceGraphTest` and `SearchServiceRerankTest` constructor calls to pass the two new args - a mock `EntityExtractor`/`EntityRepository` or a real `EntityExtractor(mockChat, "")`).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/springbootrag/service/SearchService.java src/main/java/com/example/springbootrag/repository/PgVectorRepository.java src/test/java/com/example/springbootrag/service/SearchServiceSemanticTest.java src/test/java/com/example/springbootrag/service/SearchServiceGraphTest.java src/test/java/com/example/springbootrag/service/SearchServiceRerankTest.java
git commit -m "feat(graph): semantic expansion via query entities and entity_edge neighbors"
```

---

### Task 6: Orphan-reconnection integration test (headline behavior)

**Files:**
- Test: `src/test/java/com/example/springbootrag/integration/OrphanReconnectionIntegrationTest.java`

**Interfaces:**
- Consumes: full stack (`IngestService`, `SearchService`) with `edges=both`, a deterministic fake `ChatProvider` (fixed entity JSON) and fake embeddings.

This task is test-only: it proves the spec's headline claim - an orphan page (no inbound links) that shares an entity with a well-known page IS retrieved by `graph` but is NOT a top hit under `hybrid`.

- [ ] **Step 1: Write the test**

```java
package com.example.springbootrag.integration;

import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.service.IngestService;
import com.example.springbootrag.service.SearchService;
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

@SpringBootTest(properties = "app.graph.edges=both")
@Testcontainers
class OrphanReconnectionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }

    @Autowired IngestService ingest;
    @Autowired SearchService search;
    @Autowired JdbcTemplate jdbc;

    private long projectId() {
        return jdbc.queryForObject("SELECT id FROM projects ORDER BY id LIMIT 1", Long.class);
    }

    @Test
    void orphanPageReconnectedViaSharedEntity() {
        long p = projectId();
        // Well-known page: mentions the shared entity + words matching the query.
        ingest.ingestMarkdown(p, "Known-Page", "Known-Page.md",
                "# Known Page\n\nThe PaymentsService handles refunds.", Instant.now());
        // Orphan page: NO links to/from it, but also mentions PaymentsService.
        ingest.ingestMarkdown(p, "Orphan-Page", "Orphan-Page.md",
                "# Orphan Page\n\nLegacy notes about PaymentsService retries.", Instant.now());

        // Provide a deterministic ChatProvider (as in SemanticIngestIntegrationTest) that
        // returns {"entities":[{"name":"PaymentsService","type":"service"}],"relations":[]}
        // for any text, so both pages link to the same entity and the query matches it.

        List<SearchHit> graph = search.search("graph", "PaymentsService", 10, List.of(p), List.of());
        assertThat(graph).extracting(SearchHit::docId).contains("Orphan-Page");
    }
}
```

Reuse the deterministic `ChatProvider` + fake-embedding `@TestConfiguration` from `SemanticIngestIntegrationTest` (extract it to a shared test helper if convenient).

- [ ] **Step 2: Run test to verify it passes**

Run: `mvnw.cmd -Dtest=OrphanReconnectionIntegrationTest test`
Expected: PASS - the orphan is retrieved by `graph` through the shared `PaymentsService` entity.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/example/springbootrag/integration/OrphanReconnectionIntegrationTest.java
git commit -m "test(graph): orphan page reconnected via shared entity"
```

---

## Self-Review

**Spec coverage (Phase 2 slice of `2026-07-04-graphrag-wiki-retrieval-design.md`):**
- 3b semantic edges (entity extraction) -> Tasks 2, 4. ✅
- 4 data model `entity`/`chunk_entity`/`entity_edge` -> Task 1. ✅
- 5 ingest semantic + cascade GC -> Task 4. ✅
- 6 retrieval semantic expansion + fallback -> Task 5. ✅
- 7 config `extract-model`, `min-mentions`, `edges` default `both` -> Task 4. ✅
- 8 orphan-reconnection integration test -> Task 6. ✅
- 10 risks: best-effort extraction (never breaks ingest) -> Task 2; `min-mentions` noise floor -> Tasks 3, 5; hybrid UNION preserved from Phase 1 -> Task 5. ✅

**Placeholder scan:** no TBD/TODO. Two tests require a deterministic `ChatProvider` test double; the exact JSON and the pattern to copy (`SemanticIngestIntegrationTest`) are specified, not left vague.

**Type consistency:** `ExtractedGraph.Entity(name,type)` / `Relation(src,rel,dst)` used identically in Tasks 2, 4, 5. `EntityRepository` method names (`upsertEntity`, `linkChunk`, `insertEdge`, `matchEntityIds`, `neighborEntityIds`, `chunkIdsForEntities`, `gcOrphanEntities`) match across Tasks 3, 4, 5. `SearchService` constructor final arg order: `(embed, fts, vec, qdrant, reranker, rerankProps, docEdges, graphProps, entityExtractor, entityRepo)` - used consistently in Task 5 test and noted for the Phase 1 test updates. `chunksByIds` (Task 5) and `chunksByDocIds` (Phase 1) are distinct methods, both 8-arg `SearchHit`.
