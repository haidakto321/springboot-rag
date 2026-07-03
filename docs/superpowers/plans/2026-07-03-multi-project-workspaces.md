# Multi-Project Workspaces Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user keep documents in separate projects (optionally grouped), switch the active project, and scope search/ask/compare to a project or its whole group.

**Architecture:** Add a `projects` table and a `project_id` FK on `chunks` (and Qdrant payload). "Group" is a nullable `group_name` label on the project. An active-project id (from the UI) is threaded through ingest and retrieval; a `group` flag widens the scope to the project ids sharing a group name. Retrieval filtering reuses the existing Unit-C in-query filter pattern, now keyed on `project_id` and combinable with the existing `doc_id` filter.

**Tech Stack:** Java 21 (compiles on 25), Spring Boot 3.5.6 (Spring MVC, `RestClient`, `JdbcTemplate`), Postgres + pgvector, Qdrant (gRPC client), Ollama; JUnit 5 + Mockito + AssertJ + Testcontainers; plain HTML/CSS/JS frontend. Build with `./mvnw` (never `mvn`).

## Global Constraints

- Java over Kotlin. No new dependencies without asking. No Lombok.
- Build tool is the wrapper: `./mvnw` (Linux/Git Bash) or `mvnw.cmd` (Windows).
- Server port is **8085**. App runs via `spring-boot:run` (no devtools) - Java changes need a restart; static files are live.
- Frontend stays plain HTML/CSS/JS (no framework), matching `src/main/resources/static/`.
- Filtering is applied IN the query (SQL / Qdrant filter), never post-filter, or `topK` drops valid hits.
- Backward compatibility: legacy flat endpoints keep working by targeting a seeded `Default` project.
- Commit style: Conventional Commits, English. Do NOT add a `Co-Authored-By` trailer.
- Test model quirk: chat uses qwen3 with `think:false` + `/no_think` + `<think>` stripping; unaffected here but keep when touching chat.

---

## File Structure

**Create**
- `src/main/java/com/example/springbootrag/model/Project.java` - project record.
- `src/main/java/com/example/springbootrag/repository/ProjectRepository.java` - CRUD + scope resolution.
- `src/main/java/com/example/springbootrag/service/ProjectService.java` - project ops + `resolveScope`.
- `src/main/java/com/example/springbootrag/web/ProjectController.java` - project + group endpoints, project-scoped document endpoints.
- `src/main/java/com/example/springbootrag/web/dto/ProjectSummary.java`, `ProjectRequest.java`.
- Tests mirroring each of the above under `src/test/...`.

**Modify**
- `src/main/resources/schema.sql` - projects table, `chunks.project_id`, Default backfill, index.
- `repository/PgVectorRepository.java`, `PgFtsRepository.java`, `QdrantRepository.java`, `DocFilter.java` - add a `projectIds` filter alongside `docIds`; project-scope `insert`/`upsert`/`listDocuments`/`listChunks`/`deleteByDocId`.
- `service/IngestService.java`, `SearchService.java`, `AskService.java`, `ChatService.java` - thread `projectId`/`projectIds`.
- `web/DocumentController.java`, `SearchController.java`, `AskController.java`, `ChatController.java`, `IngestController.java`, `web/dto/ChatRequest.java` - add `projectId`/`group`; repoint legacy routes at Default.
- `src/main/resources/static/index.html`, `app.js`, `style.css` - project switcher, manage modal, group toggle, project-scoped fetches.

---

# PHASE 1 - Schema + migration

### Task 1: projects table and chunks.project_id

**Files:**
- Modify: `src/main/resources/schema.sql`
- Test: `src/test/java/com/example/springbootrag/integration/ProjectSchemaIntegrationTest.java` (create)

**Interfaces:**
- Produces: table `projects(id BIGINT pk, name varchar, group_name varchar null, created_at)`; column `chunks.project_id BIGINT NOT NULL` (FK, cascade); a seeded `Default` project owning all pre-existing chunks.

- [ ] **Step 1: Write the failing test**

```java
// ProjectSchemaIntegrationTest.java - same container setup as DocumentIntegrationTest
// (copy the @Container postgres + @DynamicPropertySource + FakeEmbeddingConfig block).
@Test
void existingChunksAreBackfilledToDefaultProject() {
    // ingest a chunk the legacy way (no project) then assert it has a project_id
    ingestService.ingest("legacy", "some legacy text");   // legacy signature, see Task 8 note
    Integer withProject = jdbc.queryForObject(
        "SELECT count(*) FROM chunks WHERE doc_id = 'legacy' AND project_id IS NOT NULL", Integer.class);
    assertThat(withProject).isGreaterThan(0);
    String defaultName = jdbc.queryForObject(
        "SELECT name FROM projects WHERE id = (SELECT project_id FROM chunks WHERE doc_id='legacy' LIMIT 1)",
        String.class);
    assertThat(defaultName).isEqualTo("Default");
}
```
(Autowire `JdbcTemplate jdbc` and `IngestService ingestService`.)

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw test -Dtest=ProjectSchemaIntegrationTest`
Expected: FAIL (column `project_id` does not exist).

- [ ] **Step 3: Extend schema.sql**

Append to `src/main/resources/schema.sql`:
```sql
CREATE TABLE IF NOT EXISTS projects (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    group_name VARCHAR(255),
    created_at TIMESTAMP DEFAULT now()
);

-- Seed a Default project (only if the table is empty).
INSERT INTO projects (name)
SELECT 'Default' WHERE NOT EXISTS (SELECT 1 FROM projects);

ALTER TABLE chunks ADD COLUMN IF NOT EXISTS project_id BIGINT;

-- Backfill any chunk without a project to the Default project.
UPDATE chunks
SET project_id = (SELECT id FROM projects WHERE name = 'Default' ORDER BY id LIMIT 1)
WHERE project_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_chunks_project ON chunks (project_id);
```
Note: leave `project_id` nullable at the column level (the NOT NULL contract is enforced by the ingest path which always supplies it; a hard `SET NOT NULL` would fail the idempotent re-run guard-free). Add a FK without NOT NULL:
```sql
DO $$ BEGIN
    ALTER TABLE chunks ADD CONSTRAINT fk_chunks_project
        FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
```

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw test -Dtest=ProjectSchemaIntegrationTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/schema.sql src/test/java/com/example/springbootrag/integration/ProjectSchemaIntegrationTest.java
git commit -m "feat(db): add projects table and chunks.project_id with Default backfill"
```

---

# PHASE 2 - Project & group CRUD

### Task 2: Project record + ProjectRepository

**Files:**
- Create: `model/Project.java`, `web/dto/ProjectSummary.java`, `repository/ProjectRepository.java`
- Test: `src/test/java/com/example/springbootrag/integration/ProjectRepositoryIntegrationTest.java`

**Interfaces:**
- Produces:
  - `record Project(long id, String name, String groupName)`
  - `record ProjectSummary(long id, String name, String groupName, int docCount, int chunkCount)`
  - `ProjectRepository`:
    - `long create(String name, String groupName)`
    - `List<ProjectSummary> listWithCounts()`
    - `Optional<Project> find(long id)`
    - `void rename(long id, String name)`
    - `void setGroup(long id, String groupName)` (null clears)
    - `void delete(long id)` (cascades chunks via FK)
    - `List<String> listGroups()` (distinct non-null group_name)
    - `List<Long> idsInGroup(String groupName)`

- [ ] **Step 1: Write the failing test**

```java
// container setup as in DocumentIntegrationTest; autowire ProjectRepository repo, IngestService.
@Test
void createListRenameGroupDelete() {
    long fe = repo.create("Frontend", "MyApp");
    long be = repo.create("Backend", "MyApp");
    long solo = repo.create("Scratch", null);

    assertThat(repo.listGroups()).containsExactly("MyApp");
    assertThat(repo.idsInGroup("MyApp")).containsExactlyInAnyOrder(fe, be);

    repo.rename(fe, "Web");
    assertThat(repo.find(fe).orElseThrow().name()).isEqualTo("Web");

    repo.setGroup(solo, "MyApp");
    assertThat(repo.idsInGroup("MyApp")).contains(solo);

    repo.delete(be);
    assertThat(repo.find(be)).isEmpty();
}

@Test
void listWithCountsReportsDocAndChunkTotals() {
    long p = repo.create("P", null);
    ingestService.ingestMarkdown(p, "d", "d.md", "# A\n\ntext body here");
    ProjectSummary s = repo.listWithCounts().stream().filter(x -> x.id() == p).findFirst().orElseThrow();
    assertThat(s.docCount()).isEqualTo(1);
    assertThat(s.chunkCount()).isGreaterThan(0);
}
```
(`ingestMarkdown(long projectId, ...)` is the Task 8 signature; if implementing strictly in order, stub this second test until Task 8 or insert the chunk via `jdbc`.)

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw test -Dtest=ProjectRepositoryIntegrationTest`
Expected: FAIL (ProjectRepository missing).

- [ ] **Step 3: Implement the record and repository**

```java
// model/Project.java
package com.example.springbootrag.model;
public record Project(long id, String name, String groupName) {}
```
```java
// web/dto/ProjectSummary.java
package com.example.springbootrag.web.dto;
public record ProjectSummary(long id, String name, String groupName, int docCount, int chunkCount) {}
```
```java
// repository/ProjectRepository.java
package com.example.springbootrag.repository;

import com.example.springbootrag.model.Project;
import com.example.springbootrag.web.dto.ProjectSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ProjectRepository {
    private final JdbcTemplate jdbc;
    public ProjectRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long create(String name, String groupName) {
        return jdbc.queryForObject(
            "INSERT INTO projects (name, group_name) VALUES (?, ?) RETURNING id",
            Long.class, name, groupName);
    }

    public Optional<Project> find(long id) {
        return jdbc.query("SELECT id, name, group_name FROM projects WHERE id = ?",
            (rs, n) -> new Project(rs.getLong("id"), rs.getString("name"), rs.getString("group_name")), id)
            .stream().findFirst();
    }

    public List<ProjectSummary> listWithCounts() {
        return jdbc.query("""
            SELECT p.id, p.name, p.group_name,
                   count(DISTINCT c.doc_id) AS doc_count,
                   count(c.id)              AS chunk_count
            FROM projects p LEFT JOIN chunks c ON c.project_id = p.id
            GROUP BY p.id, p.name, p.group_name
            ORDER BY p.group_name NULLS LAST, p.name
            """,
            (rs, n) -> new ProjectSummary(rs.getLong("id"), rs.getString("name"),
                rs.getString("group_name"), rs.getInt("doc_count"), rs.getInt("chunk_count")));
    }

    public void rename(long id, String name) { jdbc.update("UPDATE projects SET name = ? WHERE id = ?", name, id); }
    public void setGroup(long id, String groupName) { jdbc.update("UPDATE projects SET group_name = ? WHERE id = ?", groupName, id); }
    public void delete(long id) { jdbc.update("DELETE FROM projects WHERE id = ?", id); }

    public List<String> listGroups() {
        return jdbc.queryForList(
            "SELECT DISTINCT group_name FROM projects WHERE group_name IS NOT NULL ORDER BY group_name", String.class);
    }
    public List<Long> idsInGroup(String groupName) {
        return jdbc.queryForList("SELECT id FROM projects WHERE group_name = ?", Long.class, groupName);
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw test -Dtest=ProjectRepositoryIntegrationTest`
Expected: PASS (the counts test may need Task 8; if so, keep only the CRUD test now and add the counts test with Task 8's commit).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/springbootrag/model/Project.java src/main/java/com/example/springbootrag/web/dto/ProjectSummary.java src/main/java/com/example/springbootrag/repository/ProjectRepository.java src/test/java/com/example/springbootrag/integration/ProjectRepositoryIntegrationTest.java
git commit -m "feat(projects): add Project model and ProjectRepository CRUD"
```

### Task 3: ProjectService with resolveScope

**Files:**
- Create: `service/ProjectService.java`
- Test: `src/test/java/com/example/springbootrag/service/ProjectServiceTest.java`

**Interfaces:**
- Consumes: `ProjectRepository`.
- Produces: `ProjectService`:
  - `long create(String name, String groupName)` (rejects blank name)
  - `List<ProjectSummary> list()`, `List<String> groups()`
  - `void rename(long id, String name)`, `void setGroup(long id, String groupName)`, `void delete(long id)`
  - `long defaultProjectId()` (id of the `Default` project, else first project)
  - `List<Long> resolveScope(long projectId, boolean group)` - `[projectId]`, or all ids sharing that project's non-null `group_name` when `group==true` (falls back to `[projectId]` if the project has no group).

- [ ] **Step 1: Write the failing test** (Mockito over `ProjectRepository`)

```java
class ProjectServiceTest {
    ProjectRepository repo = mock(ProjectRepository.class);
    ProjectService svc = new ProjectService(repo);

    @Test void resolveScopeSingleProjectWhenNotGroup() {
        assertThat(svc.resolveScope(5, false)).containsExactly(5L);
        verify(repo, never()).idsInGroup(any());
    }
    @Test void resolveScopeExpandsToGroupWhenRequested() {
        when(repo.find(5)).thenReturn(Optional.of(new Project(5, "FE", "MyApp")));
        when(repo.idsInGroup("MyApp")).thenReturn(List.of(5L, 6L));
        assertThat(svc.resolveScope(5, true)).containsExactlyInAnyOrder(5L, 6L);
    }
    @Test void resolveScopeGroupFallsBackWhenUngrouped() {
        when(repo.find(5)).thenReturn(Optional.of(new Project(5, "Solo", null)));
        assertThat(svc.resolveScope(5, true)).containsExactly(5L);
    }
    @Test void createRejectsBlankName() {
        assertThatThrownBy(() -> svc.create("  ", null)).isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `./mvnw test -Dtest=ProjectServiceTest` → FAIL.

- [ ] **Step 3: Implement**

```java
package com.example.springbootrag.service;

import com.example.springbootrag.model.Project;
import com.example.springbootrag.repository.ProjectRepository;
import com.example.springbootrag.web.dto.ProjectSummary;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProjectService {
    private final ProjectRepository repo;
    public ProjectService(ProjectRepository repo) { this.repo = repo; }

    public long create(String name, String groupName) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("project name is required");
        return repo.create(name.strip(), blankToNull(groupName));
    }
    public List<ProjectSummary> list() { return repo.listWithCounts(); }
    public List<String> groups() { return repo.listGroups(); }
    public void rename(long id, String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("project name is required");
        repo.rename(id, name.strip());
    }
    public void setGroup(long id, String groupName) { repo.setGroup(id, blankToNull(groupName)); }
    public void delete(long id) { repo.delete(id); }

    public long defaultProjectId() {
        return repo.listWithCounts().stream()
            .filter(p -> p.name().equals("Default")).map(ProjectSummary::id).findFirst()
            .orElseGet(() -> repo.listWithCounts().stream().map(ProjectSummary::id).findFirst()
                .orElseThrow(() -> new IllegalStateException("no projects exist")));
    }

    public List<Long> resolveScope(long projectId, boolean group) {
        if (!group) return List.of(projectId);
        Project p = repo.find(projectId).orElse(null);
        if (p == null || p.groupName() == null) return List.of(projectId);
        List<Long> ids = repo.idsInGroup(p.groupName());
        return ids.isEmpty() ? List.of(projectId) : ids;
    }

    private static String blankToNull(String s) { return (s == null || s.isBlank()) ? null : s.strip(); }
}
```

- [ ] **Step 4: Run to verify it passes** — `./mvnw test -Dtest=ProjectServiceTest` → PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/springbootrag/service/ProjectService.java src/test/java/com/example/springbootrag/service/ProjectServiceTest.java
git commit -m "feat(projects): add ProjectService with group scope resolution"
```

### Task 4: ProjectController (project + group endpoints)

**Files:**
- Create: `web/ProjectController.java`, `web/dto/ProjectRequest.java`
- Test: `src/test/java/com/example/springbootrag/web/ProjectControllerTest.java` (`@WebMvcTest(ProjectController.class)`, `@MockBean ProjectService`)

**Interfaces:**
- Consumes: `ProjectService`.
- Produces: `POST /projects` `{name, groupName?}`; `GET /projects`; `PATCH /projects/{id}` `{name?, groupName?}`; `DELETE /projects/{id}`; `GET /groups`.

- [ ] **Step 1: Write the failing test**

```java
@WebMvcTest(ProjectController.class)
class ProjectControllerTest {
    @Autowired MockMvc mvc;
    @MockBean ProjectService svc;

    @Test void createReturnsProject() throws Exception {
        when(svc.create("FE", "MyApp")).thenReturn(7L);
        mvc.perform(post("/projects").contentType("application/json")
                .content("{\"name\":\"FE\",\"groupName\":\"MyApp\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.id").value(7));
    }
    @Test void listReturnsProjects() throws Exception {
        when(svc.list()).thenReturn(List.of(new ProjectSummary(1,"Default",null,2,10)));
        mvc.perform(get("/projects")).andExpect(status().isOk())
           .andExpect(jsonPath("$[0].name").value("Default"));
    }
    @Test void blankNameIsBadRequest() throws Exception {
        when(svc.create(any(), any())).thenThrow(new IllegalArgumentException("project name is required"));
        mvc.perform(post("/projects").contentType("application/json").content("{\"name\":\"\"}"))
           .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `./mvnw test -Dtest=ProjectControllerTest` → FAIL.

- [ ] **Step 3: Implement**

```java
// web/dto/ProjectRequest.java
package com.example.springbootrag.web.dto;
public record ProjectRequest(String name, String groupName) {}
```
```java
// web/ProjectController.java
package com.example.springbootrag.web;

import com.example.springbootrag.service.ProjectService;
import com.example.springbootrag.web.dto.ProjectRequest;
import com.example.springbootrag.web.dto.ProjectSummary;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class ProjectController {
    private final ProjectService projects;
    public ProjectController(ProjectService projects) { this.projects = projects; }

    @PostMapping("/projects")
    public Map<String, Long> create(@RequestBody ProjectRequest req) {
        return Map.of("id", projects.create(req.name(), req.groupName()));
    }
    @GetMapping("/projects")
    public List<ProjectSummary> list() { return projects.list(); }

    @PatchMapping("/projects/{id}")
    public void update(@PathVariable long id, @RequestBody ProjectRequest req) {
        if (req.name() != null) projects.rename(id, req.name());
        // groupName present (even null) => set/clear. Distinguish "absent" via a sentinel is overkill here;
        // the UI always sends groupName on a group change.
        projects.setGroup(id, req.groupName());
    }
    @DeleteMapping("/projects/{id}")
    public void delete(@PathVariable long id) { projects.delete(id); }

    @GetMapping("/groups")
    public List<String> groups() { return projects.groups(); }
}
```

- [ ] **Step 4: Run to verify it passes** — `./mvnw test -Dtest=ProjectControllerTest` → PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/springbootrag/web/ProjectController.java src/main/java/com/example/springbootrag/web/dto/ProjectRequest.java src/test/java/com/example/springbootrag/web/ProjectControllerTest.java
git commit -m "feat(projects): add project and group REST endpoints"
```

---

# PHASE 3 - Project-scoped ingest & retrieval

### Task 5: DocFilter gains a project_id clause; Pg repos filter by project

**Files:**
- Modify: `repository/DocFilter.java`, `repository/PgVectorRepository.java`, `repository/PgFtsRepository.java`
- Test: extend `src/test/java/com/example/springbootrag/integration/SearchIntegrationTest.java`

**Interfaces:**
- Produces: `PgFtsRepository.search(String q, int topK, List<Long> projectIds, List<String> docIds)` and `PgVectorRepository.search(float[] vec, int topK, List<Long> projectIds, List<String> docIds)`. `projectIds` empty = all (compat only). `DocFilter.inClauseLong(column, ids)` mirroring the existing string version.

- [ ] **Step 1: Write the failing test** (add to SearchIntegrationTest)

```java
@Test
void searchFiltersByProject() {
    long a = projectRepository.create("A", null);
    long b = projectRepository.create("B", null);
    ingestService.ingest(a, "d1", "hydraulic pressure drop on line 3");
    ingestService.ingest(b, "d2", "hydraulic pressure drop on line 3");

    var onlyA = searchService.search("pgvector", "pressure", 10, List.of(a), List.of());
    assertThat(onlyA).extracting(SearchHit::docId).containsOnly("d1");
}
```
(Autowire `ProjectRepository projectRepository`. `ingest(long, String, String)` and `search(..., projectIds, docIds)` are the new signatures from Tasks 5-8.)

- [ ] **Step 2: Run to verify it fails** — `./mvnw test -Dtest=SearchIntegrationTest` → FAIL (compile: signatures).

- [ ] **Step 3: Implement**

In `DocFilter.java` add:
```java
static boolean active(List<?> ids) { return ids != null && !ids.isEmpty(); }   // generalize existing
static String placeholders(int n) { return "?,".repeat(n - 1) + "?"; }
```
In `PgVectorRepository.search`, build the WHERE from BOTH filters (project ids are longs, doc ids are strings):
```java
public List<SearchHit> search(float[] queryEmbedding, int topK, List<Long> projectIds, List<String> docIds) {
    StringBuilder where = new StringBuilder();
    List<Object> args = new ArrayList<>();
    args.add(toVectorLiteral(queryEmbedding));
    if (DocFilter.active(projectIds)) {
        where.append(where.isEmpty() ? " WHERE" : " AND").append(" project_id IN (").append(DocFilter.placeholders(projectIds.size())).append(")");
        args.addAll(projectIds);
    }
    if (DocFilter.active(docIds)) {
        where.append(where.isEmpty() ? " WHERE" : " AND").append(" doc_id IN (").append(DocFilter.placeholders(docIds.size())).append(")");
        args.addAll(docIds);
    }
    args.add(topK);
    return jdbc.query(
        "SELECT id, doc_id, chunk_index, content, source_file, heading_path, " +
        "       embedding <=> ?::vector AS distance FROM chunks" + where + " ORDER BY distance ASC LIMIT ?",
        (rs, n) -> new SearchHit(rs.getLong("id"), rs.getString("doc_id"), rs.getInt("chunk_index"),
            rs.getString("content"), rs.getString("source_file"), rs.getString("heading_path"),
            1.0 - rs.getDouble("distance")),
        args.toArray());
}
```
Apply the same two-filter pattern to `PgFtsRepository.search` (its base WHERE is `tsv @@ ...`, so both project and doc clauses are ` AND ...`). Keep the query/query/args ordering: `q`, `q`, then project ids, then doc ids, then `topK`.

- [ ] **Step 4: Run to verify it passes** — `./mvnw test -Dtest=SearchIntegrationTest` → PASS (after Tasks 6-8 compile). If executing strictly in order, this task's test compiles only once Tasks 6-8 land; keep the pg-repo edits here and move the assertion to Task 8's commit.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/springbootrag/repository/DocFilter.java src/main/java/com/example/springbootrag/repository/PgVectorRepository.java src/main/java/com/example/springbootrag/repository/PgFtsRepository.java
git commit -m "feat(search): project_id filter in Postgres repositories"
```

### Task 6: Qdrant carries and filters by project_id

**Files:**
- Modify: `repository/QdrantRepository.java`
- Test: covered by SearchIntegrationTest (qdrant assertion in Task 8).

**Interfaces:**
- Produces: `upsert(long id, long projectId, String docId, int chunkIndex, String content, String sourceFile, String headingPath, float[] embedding)`; `search(float[] vec, int topK, List<Long> projectIds, List<String> docIds)`; `deleteByProject(long projectId)`.

- [ ] **Step 1: Add project_id to payload** in `upsert`:
```java
payload.put("project_id", value(projectId));
```
- [ ] **Step 2: Filter in `search`** - combine project and doc conditions. Qdrant `should` = OR within a group of conditions; to AND the two filters, use two `must` sub-filters, each an OR over its values:
```java
if ((projectIds != null && !projectIds.isEmpty()) || (docIds != null && !docIds.isEmpty())) {
    Points.Filter.Builder filter = Points.Filter.newBuilder();
    if (projectIds != null && !projectIds.isEmpty()) {
        Points.Filter.Builder pf = Points.Filter.newBuilder();
        for (Long pid : projectIds) pf.addShould(match("project_id", pid));   // integer match
        filter.addMust(Points.Condition.newBuilder().setFilter(pf).build());
    }
    if (docIds != null && !docIds.isEmpty()) {
        Points.Filter.Builder df = Points.Filter.newBuilder();
        for (String d : docIds) df.addShould(matchKeyword("doc_id", d));
        filter.addMust(Points.Condition.newBuilder().setFilter(df).build());
    }
    search.setFilter(filter.build());
}
```
Use `io.qdrant.client.ConditionFactory.match(String, long)` for the integer field (import it). Add `deleteByProject(long)` mirroring `deleteByDocId` but `match("project_id", projectId)`.
- [ ] **Step 3: Run** `./mvnw test -Dtest=SearchIntegrationTest` (green after Task 8). 
- [ ] **Step 4: Commit**
```bash
git add src/main/java/com/example/springbootrag/repository/QdrantRepository.java
git commit -m "feat(search): project_id in Qdrant payload and filter"
```

### Task 7: PgVector listDocuments / listChunks / delete become project-scoped

**Files:** Modify `repository/PgVectorRepository.java`
**Interfaces:** `listDocuments(long projectId)`, `listChunks(long projectId, String docId)`, `deleteByDocId(long projectId, String docId)`, `insert(long projectId, String docId, ...)`.

- [ ] **Step 1:** Add `project_id` to the `insert` column list/values. Add `WHERE project_id = ?` to `listDocuments`; `WHERE project_id = ? AND doc_id = ?` to `listChunks` and `deleteByDocId`. 
- [ ] **Step 2:** Run `./mvnw test -Dtest=DocumentIntegrationTest` (will fail until Task 8 wires callers; expected).
- [ ] **Step 3: Commit**
```bash
git add src/main/java/com/example/springbootrag/repository/PgVectorRepository.java
git commit -m "feat(documents): scope pgvector document/chunk queries by project"
```

### Task 8: Thread projectId through IngestService, SearchService, AskService, ChatService

**Files:** Modify `service/IngestService.java`, `SearchService.java`, `AskService.java`, `ChatService.java`
**Interfaces:**
- `IngestService.ingest(long projectId, String docId, String text)`, `ingestMarkdown(long projectId, String docId, String sourceFile, String md)`, `ingestChunks(long projectId, ...)`, `delete(long projectId, String docId)`.
- `SearchService.search(String type, String q, int topK, List<Long> projectIds, List<String> docIds)` and `compare(String q, int topK, List<Long> projectIds, List<String> docIds)`.
- `AskService.ask(String q, List<Long> projectIds)`; `ChatService.chatStream(List<ChatMessage> history, List<Long> projectIds, List<String> docIds, Consumer<String> onToken)`.

- [ ] **Step 1: Update `IngestService`** - carry `projectId` into `pgVector.insert(...)`, `qdrant.upsert(...)`, and `delete`. `delete(projectId, docId)` calls `pgVector.deleteByDocId(projectId, docId)` and `qdrant` delete (delete points where `project_id AND doc_id` - add an overloaded Qdrant delete or filter both). Keep the legacy 2-arg `ingest`/`delete` as thin wrappers that resolve `projectService.defaultProjectId()` (inject `ProjectService`) - preserves old tests/endpoints.
- [ ] **Step 2: Update `SearchService`** - add `projectIds` param to `search`/`compare` and pass to every repo call (`fts.search`, `pgVector.search`, `qdrantSearch`, `hybrid`, `rerank`). Keep prior overloads delegating with `List.of()` project ids.
- [ ] **Step 3: Update `AskService.ask` and `ChatService.chatStream`** to accept `projectIds` and pass into `searchService.search("rerank", q, contextChunks, projectIds, docIds)`. In ChatService keep the condense-question path; retrieval call gains `projectIds`.
- [ ] **Step 4: Update the existing service unit tests** (`AskServiceTest`, `ChatServiceTest`, `SearchServiceRerankTest`) to the new signatures (add `List.of()`/`anyList()` for the new param), and enable the Task-2/Task-5 assertions that were deferred.
- [ ] **Step 5: Run** `./mvnw test -Dtest='SearchServiceRerankTest,AskServiceTest,ChatServiceTest,SearchIntegrationTest,ProjectRepositoryIntegrationTest'` → PASS.
- [ ] **Step 6: Commit**
```bash
git add src/main/java/com/example/springbootrag/service src/test/java/com/example/springbootrag/service src/test/java/com/example/springbootrag/integration
git commit -m "feat(projects): thread project scope through ingest and retrieval services"
```

### Task 9: Controllers - project-scoped documents + projectId/group params; repoint legacy

**Files:** Modify `web/DocumentController.java`, `SearchController.java`, `AskController.java`, `ChatController.java`, `IngestController.java`, `web/dto/ChatRequest.java`; add project-scoped document routes.
**Interfaces:**
- `POST/GET /projects/{projectId}/documents`, `DELETE /projects/{projectId}/documents/{docId}`, `GET /projects/{projectId}/documents/{docId}/chunks`.
- `/search`,`/compare`,`/ask` gain `@RequestParam(required=false) Long projectId` + `@RequestParam(defaultValue="false") boolean group`; resolve via `ProjectService.resolveScope`. When `projectId` is null (legacy call), use `defaultProjectId()`.
- `ChatRequest` gains `Long projectId`, `boolean group`.

- [ ] **Step 1:** Add the project-scoped document endpoints to `DocumentController` (inject `ProjectService`), delegating to the new `IngestService`/`PgVectorRepository` project-scoped methods. Keep legacy `/documents*` mapped to `defaultProjectId()`.
- [ ] **Step 2:** Add `projectId`+`group` to `SearchController`, `AskController`; resolve scope: `List<Long> scope = projectService.resolveScope(projectId != null ? projectId : projectService.defaultProjectId(), group);` and pass to the service.
- [ ] **Step 3:** `ChatController` reads `req.projectId()`/`req.group()`, resolves scope, passes to `chatService.chatStream(messages, scope, docIds, onToken)`.
- [ ] **Step 4:** Update `ChatControllerTest` mock (`chatStream(anyList(), anyList(), any(), any())`) and add a `DocumentIntegrationTest` case for `POST /projects/{id}/documents` round-trip.
- [ ] **Step 5:** Run `./mvnw test` (full suite) → all green (2 DJL skips expected).
- [ ] **Step 6: Commit**
```bash
git add src/main/java/com/example/springbootrag/web src/test/java/com/example/springbootrag
git commit -m "feat(projects): project-scoped document routes and projectId/group params"
```

---

# PHASE 4 - UI

### Task 10: Project switcher + manage modal

**Files:** Modify `static/index.html`, `static/app.js`, `static/style.css`
**Interfaces:** consumes `/projects`, `/groups`, `POST/PATCH/DELETE /projects`. Produces JS globals `activeProjectId`, `projectFetch(path)` (prefixes `/projects/{activeProjectId}`), and re-renders on project change.

- [ ] **Step 1:** Add a `.project-switcher` block to the sidebar under `.brand`: a `<select id="project-select">` (options grouped with `<optgroup label="MyApp">`, ungrouped under a "No group" optgroup), plus `＋ New` and `Manage` buttons. Add a hidden `.modal` overlay `#project-modal` with a create form (name + group `<input list="group-list">` + `<datalist id="group-list">`) and a project list with rename/delete/group controls.
- [ ] **Step 2:** In `app.js`: `loadProjects()` fetches `/projects`, populates the select (grouped), restores `activeProjectId` from `localStorage('kb-project')` (or first/Default), and calls `refreshDocs()`. On select change → set active, persist, `refreshDocs()` + clear search/chat. Wire modal create/rename/delete/group to the endpoints then `loadProjects()`. Populate `#group-list` from `/groups`.
- [ ] **Step 3:** CSS for `.project-switcher`, `.modal`/`.modal-card` (reuse toast/card tokens), grouped select styling.
- [ ] **Step 4 (manual verify):** restart app (`./mvnw spring-boot:run`), open `http://localhost:8085/`, create "Frontend" (group MyApp) and "Backend" (group MyApp), switch between them, rename, delete. Confirm the select groups them under "MyApp".
- [ ] **Step 5: Commit**
```bash
git add src/main/resources/static/index.html src/main/resources/static/app.js src/main/resources/static/style.css
git commit -m "feat(ui): project switcher and manage modal"
```

### Task 11: Point documents/search/ask/compare at the active project + group toggle

**Files:** Modify `static/index.html`, `static/app.js`
**Interfaces:** consumes the project-scoped document routes and `projectId`/`group` query params.

- [ ] **Step 1:** Change `refreshDocs()` to `GET /projects/{activeProjectId}/documents`; upload XHR to `POST /projects/{activeProjectId}/documents`; delete + chunk-view to the project-scoped routes.
- [ ] **Step 2:** Add `&projectId=${activeProjectId}&group=${groupSearch}` to `appendScope(...)` (search + compare) and `projectId`/`group` to the chat POST body.
- [ ] **Step 3:** Add a `<label class="group-toggle"><input type="checkbox" id="group-search"> Search whole group (<name>)</label>` inside each `.scope-bar`, shown only when the active project has a `groupName`; bind `groupSearch` and re-render label with the group name on project change.
- [ ] **Step 4 (manual verify):** restart, upload a doc to Frontend and a different doc to Backend; confirm search in Frontend sees only its doc; tick "Search whole group" and confirm both appear; confirm open-in-context and chunk view stay within the active project.
- [ ] **Step 5: Commit**
```bash
git add src/main/resources/static/index.html src/main/resources/static/app.js
git commit -m "feat(ui): scope documents and search/ask/compare to the active project and group"
```

### Task 12: Docs

**Files:** Modify `README.md`, `docs/ROADMAP.md`, `docs/LEARNINGS.md`
- [ ] **Step 1:** README "Knowledge base" section: document projects/groups, the new `/projects` endpoints, and the project-scoped document routes.
- [ ] **Step 2:** ROADMAP: add a "Multi-project workspaces ✅ done" entry.
- [ ] **Step 3:** LEARNINGS: add a short note on modeling a hierarchy with an emergent group label vs a full entity, and applying the project filter in-query alongside the doc filter.
- [ ] **Step 4: Commit**
```bash
git add README.md docs/ROADMAP.md docs/LEARNINGS.md
git commit -m "docs: document multi-project workspaces"
```

---

## Self-Review Notes

- **Spec coverage:** data model (Task 1), projects table + CRUD (2-4), Qdrant payload (6), scoped ingest/retrieval (5-9), legacy compat via Default (8-9), UI switcher/modal/group toggle (10-11), migration/backfill (1), tests at each layer. Covered.
- **Ordering caveat:** Tasks 5-8 have interlocking signatures; some assertions only compile once Task 8 lands. Each such step calls this out and moves the assertion to Task 8's commit if executing strictly in order.
- **Type consistency:** `resolveScope(long, boolean) -> List<Long>`; repos take `List<Long> projectIds, List<String> docIds` in that order everywhere; `ingest*`/`delete` take `long projectId` first.
