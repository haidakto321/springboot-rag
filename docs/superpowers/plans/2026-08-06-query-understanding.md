# Query Understanding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn a natural-language question into a validated metadata filter, apply it in `/ask` and `/chat/stream`, widen when it matches nothing, and measure the whole thing against a committed synthetic record corpus.

**Architecture:** A facet catalogue derived from the metadata actually indexed (never declared) is fed to one LLM call that returns a filter as JSON. The output is validated against that catalogue - unknown paths and ops are dropped - and rebuilt through the existing `MetadataFilter`, so extraction can never express something the DSL cannot. Retrieval runs filtered; if it returns nothing, it runs again unfiltered and the answer says so.

**Tech Stack:** Java 21 target on Java 25 runtime, Spring Boot 3.5.6, raw `JdbcTemplate`, Postgres 16 + pgvector, Qdrant v1.9.0, Jackson, JUnit 5 + Testcontainers, SnakeYAML (already used by the eval harness).

**Spec:** `docs/superpowers/specs/2026-08-06-query-understanding-design.md`

## Global Constraints

- Build and test with `./mvnw`, never `mvn`. On PowerShell quote `-D` args: `./mvnw test "-Dtest=ExtractionValidatorTest"`. Multiple classes are comma-separated, not `+`.
- No new dependencies. Jackson, SnakeYAML, JdbcTemplate, Testcontainers, JUnit 5 are all present already.
- No Lombok. Plain records and constructors.
- SQL stays visible in repositories via `JdbcTemplate`.
- `SearchContext ctx` remains the first argument of every read path, including the facet query. A facet the caller cannot read must not appear in the catalogue.
- Extraction must never fail a request. Model down, timeout, garbage output: empty filter, answer proceeds.
- An explicit caller-supplied filter always wins and skips extraction entirely.
- Schema changes go in `src/main/resources/schema.sql`, idempotent, because it re-runs on every startup.
- **Commits are the user's job.** Each task ends at "full suite green". Do not run `git add` or `git commit` unless the user asks in that same session.
- The dev stack must be running (`docker compose up -d`) or the tests that boot against `application.yml` fail with "Connection to localhost:5432 refused".
- Keep `docs/implementation-notes.md` updated with any decision that deviates from this plan.

---

## File Structure

**New main sources:**
- `config/UnderstandProperties.java` - `app.understand.*` config.
- `understand/Facet.java` - record `(String docType, String path, String type, List<String> samples, int distinctCount)`.
- `understand/FacetCatalogue.java` - caching + type inference over the repository.
- `repository/FacetRepository.java` - the recursive SQL over `chunks.metadata`.
- `understand/ExtractionValidator.java` - model JSON -> validated `MetadataFilter`. Pure.
- `understand/QueryUnderstanding.java` - prompt, one LLM call, parse, validate.
- `web/FacetController.java` - `GET /projects/{id}/facets`.

**Modified:**
- `chat/ChatProvider.java`, `chat/OllamaChatProvider.java` - a `chat(system, user, model)` overload, so `app.understand.model` actually takes effect.
- `src/main/resources/schema.sql` - two `rag_trace` columns.
- `trace/RagTrace.java`, `trace/TraceRepository.java`, `trace/TraceRecorder.java` - carry the applied filter and the widen flag.
- `service/AskService.java`, `service/ChatService.java` - extract, widen, report.
- `web/dto/AskResponse.java` - `appliedFilter`, `widened`.
- `web/ChatController.java` - `filter` NDJSON frame.
- `pom.xml` - `eval-records` in `excludedGroups`.

**New test sources:**
- `understand/ExtractionValidatorTest.java`, `understand/QueryUnderstandingTest.java`, `understand/FacetTypeInferenceTest.java`
- `integration/FacetCatalogueIntegrationTest.java`, `integration/QueryUnderstandingIntegrationTest.java`
- `eval/RecordCorpus.java`, `eval/RecordGoldenEntry.java`, `eval/RecordGoldenSet.java`, `eval/RecordFilterEvalTest.java`
- `src/test/resources/eval/records-golden.yaml`

---

## Task 1: Facet catalogue

**Files:**
- Create: `src/main/java/com/example/springbootrag/understand/Facet.java`
- Create: `src/main/java/com/example/springbootrag/repository/FacetRepository.java`
- Create: `src/main/java/com/example/springbootrag/understand/FacetCatalogue.java`
- Create: `src/main/java/com/example/springbootrag/config/UnderstandProperties.java`
- Create: `src/main/java/com/example/springbootrag/web/FacetController.java`
- Test: `src/test/java/com/example/springbootrag/understand/FacetTypeInferenceTest.java`
- Test: `src/test/java/com/example/springbootrag/integration/FacetCatalogueIntegrationTest.java`
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Consumes: `SearchContext`, `DocFilter.groupClause` (package-private, so the SQL lives in `repository`).
- Produces:
  - `record Facet(String docType, String path, String type, List<String> samples, int distinctCount)`
  - `List<Facet> FacetRepository.facets(SearchContext ctx, List<Long> projectIds, int sampleLimit)`
  - `List<Facet> FacetCatalogue.forProjects(SearchContext ctx, List<Long> projectIds)` - cached
  - `static String FacetCatalogue.inferType(List<String> samples)` - `"number"` | `"date"` | `"text"`

- [ ] **Step 1: Write the failing type-inference test**

```java
package com.example.springbootrag.understand;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FacetTypeInferenceTest {

    @Test
    void allNumbersInferNumber() {
        assertThat(FacetCatalogue.inferType(List.of("1899.5", "42", "0"))).isEqualTo("number");
    }

    @Test
    void allIsoDatesInferDate() {
        assertThat(FacetCatalogue.inferType(List.of("2026-05-02", "2026-01-14"))).isEqualTo("date");
    }

    @Test
    void anythingElseIsText() {
        assertThat(FacetCatalogue.inferType(List.of("ACME Corp", "GLOBEX"))).isEqualTo("text");
    }

    @Test
    void oneOddValueDowngradesToText() {
        // Inference only picks the cast the filter DSL will use, so a mixed column must degrade
        // to a text comparison rather than produce a cast error at query time.
        assertThat(FacetCatalogue.inferType(List.of("42", "n/a"))).isEqualTo("text");
    }

    @Test
    void noSamplesIsText() {
        assertThat(FacetCatalogue.inferType(List.of())).isEqualTo("text");
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw test "-Dtest=FacetTypeInferenceTest"`
Expected: compile error - `FacetCatalogue` does not exist.

- [ ] **Step 3: Add the config properties**

```java
package com.example.springbootrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Query understanding: turning a question into a metadata filter. */
@ConfigurationProperties(prefix = "app.understand")
public class UnderstandProperties {

    /** Off restores exactly the pre-feature behaviour. */
    private boolean enabled = true;
    /** Empty means "use app.chat.model" - a separate knob so extraction can use a smaller model. */
    private String model = "";
    private int maxConditions = 4;
    private int facetSamples = 5;
    private long facetTtlSeconds = 300;
    /** Longest value the extractor may put in a filter; anything longer is dropped. */
    private int maxValueLength = 200;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getMaxConditions() { return maxConditions; }
    public void setMaxConditions(int maxConditions) { this.maxConditions = maxConditions; }
    public int getFacetSamples() { return facetSamples; }
    public void setFacetSamples(int facetSamples) { this.facetSamples = facetSamples; }
    public long getFacetTtlSeconds() { return facetTtlSeconds; }
    public void setFacetTtlSeconds(long facetTtlSeconds) { this.facetTtlSeconds = facetTtlSeconds; }
    public int getMaxValueLength() { return maxValueLength; }
    public void setMaxValueLength(int maxValueLength) { this.maxValueLength = maxValueLength; }
}
```

Register it where the other property classes are registered - follow `TraceRecorder.Props` (a nested `@Configuration @EnableConfigurationProperties(...)`) and add the same for `UnderstandProperties` inside `FacetCatalogue`.

Add to `src/main/resources/application.yml` under `app:`:

```yaml
  understand:
    enabled: true
    model: ""            # empty = app.chat.model
    max-conditions: 4
    facet-samples: 5
    facet-ttl-seconds: 300
    max-value-length: 200
```

- [ ] **Step 4: Implement Facet and the repository**

```java
package com.example.springbootrag.understand;

import java.util.List;

/** One filterable path that actually exists in the index, with evidence of what it holds. */
public record Facet(String docType, String path, String type,
                    List<String> samples, int distinctCount) {}
```

```java
package com.example.springbootrag.repository;

import com.example.springbootrag.security.SearchContext;
import com.example.springbootrag.understand.Facet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Derives the filterable paths from the metadata that is actually indexed.
 *
 * <p>Derived, never declared: the set of document types is open, so a catalogue built from
 * configuration would be silent about exactly the tenant nobody configured. Read under the
 * caller's access labels like every other read - a facet is data about data, and listing one the
 * caller cannot read is still a leak.
 */
@Repository
public class FacetRepository {

    private final JdbcTemplate jdbc;

    public FacetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Leaf paths under the {@code values} and {@code conf} trees, with sample values and a
     * distinct count. {@code prov} is deliberately excluded: provenance is filterable, but nobody
     * asks a question about a bounding box.
     */
    public List<Facet> facets(SearchContext ctx, List<Long> projectIds, int sampleLimit) {
        if (ctx.readsNothing()) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(sampleLimit, 20));   // interpolated below: keep it sane
        String projectClause = DocFilter.active(projectIds)
                ? " AND c.project_id IN (" + DocFilter.placeholders(projectIds.size()) + ")"
                : "";
        List<Object> args = new ArrayList<>(ctx.groups());
        if (DocFilter.active(projectIds)) args.addAll(projectIds);

        String sql = """
            WITH RECURSIVE seed AS (
                SELECT c.doc_type, r.root || '.' || k.key AS path, k.value AS node
                FROM chunks c
                CROSS JOIN LATERAL (VALUES ('values', c.metadata->'values'),
                                           ('conf',   c.metadata->'conf')) AS r(root, node)
                CROSS JOIN LATERAL jsonb_each(r.node) AS k
                WHERE c.metadata <> '{}'::jsonb AND r.node IS NOT NULL
                  AND""" + DocFilter.groupClause(ctx.groups()) + projectClause + """

            ), tree AS (
                SELECT doc_type, path, node FROM seed
                UNION ALL
                SELECT t.doc_type, t.path || '.' || k.key, k.value
                FROM tree t
                CROSS JOIN LATERAL jsonb_each(t.node) AS k
                WHERE jsonb_typeof(t.node) = 'object'
            )
            SELECT doc_type, path,
                   count(DISTINCT node #>> '{}') AS distinct_count,
                   (array_agg(DISTINCT node #>> '{}' ORDER BY node #>> '{}'))[1:%d] AS samples
            FROM tree
            WHERE jsonb_typeof(node) <> 'object'
            GROUP BY doc_type, path
            ORDER BY doc_type, path
            """.formatted(limit);

        return jdbc.query(sql, (rs, n) -> new Facet(
                rs.getString("doc_type"),
                rs.getString("path"),
                "text",                        // FacetCatalogue infers the real type from samples
                toList(rs.getArray("samples")),
                rs.getInt("distinct_count")), args.toArray());
    }

    private static List<String> toList(java.sql.Array array) {
        try {
            if (array == null) return List.of();
            return Arrays.stream((Object[]) array.getArray())
                    .filter(java.util.Objects::nonNull).map(Object::toString).toList();
        } catch (java.sql.SQLException e) {
            return List.of();
        }
    }
}
```

Note the `%d` interpolation of the sample limit: it is clamped to 1..20 first, and a bind parameter is not allowed inside an array slice.

- [ ] **Step 5: Implement the catalogue with caching and inference**

```java
package com.example.springbootrag.understand;

import com.example.springbootrag.config.UnderstandProperties;
import com.example.springbootrag.repository.FacetRepository;
import com.example.springbootrag.security.SearchContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The facet list handed to the extractor, cached because it runs once per question while the
 * corpus changes far more slowly than it is queried.
 */
@Service
public class FacetCatalogue {

    private static final Logger log = LoggerFactory.getLogger(FacetCatalogue.class);

    /** Registers the properties without another @EnableConfigurationProperties elsewhere. */
    @Configuration
    @EnableConfigurationProperties(UnderstandProperties.class)
    static class Props {}

    private record Key(String groups, String projects) {}
    private record Entry(List<Facet> facets, Instant loadedAt) {}

    private final FacetRepository repo;
    private final UnderstandProperties props;
    private final Map<Key, Entry> cache = new ConcurrentHashMap<>();

    public FacetCatalogue(FacetRepository repo, UnderstandProperties props) {
        this.repo = repo;
        this.props = props;
    }

    /**
     * The cache key includes the caller's groups, so the catalogue can never leak the existence of
     * a facet belonging to documents the caller cannot read.
     */
    public List<Facet> forProjects(SearchContext ctx, List<Long> projectIds) {
        Key key = new Key(String.join(",", new java.util.TreeSet<>(ctx.groups())),
                projectIds == null ? "" : projectIds.stream().sorted().map(String::valueOf)
                        .reduce("", (a, b) -> a.isEmpty() ? b : a + "," + b));
        Entry hit = cache.get(key);
        Instant now = Instant.now();
        if (hit != null && Duration.between(hit.loadedAt(), now).getSeconds() < props.getFacetTtlSeconds()) {
            return hit.facets();
        }
        List<Facet> loaded;
        try {
            loaded = typed(repo.facets(ctx, projectIds, props.getFacetSamples()));
        } catch (RuntimeException e) {
            // A broken catalogue must degrade to "no filter", never to a failed answer.
            log.warn("facet catalogue query failed; continuing without facets", e);
            return List.of();
        }
        cache.put(key, new Entry(loaded, now));
        return loaded;
    }

    private static List<Facet> typed(List<Facet> raw) {
        List<Facet> out = new ArrayList<>(raw.size());
        for (Facet f : raw) {
            out.add(new Facet(f.docType(), f.path(), inferType(f.samples()), f.samples(), f.distinctCount()));
        }
        return out;
    }

    /** By value shape across the samples. A mixed column degrades to text, never to a cast error. */
    public static String inferType(List<String> samples) {
        if (samples == null || samples.isEmpty()) return "text";
        boolean allNumbers = true;
        boolean allDates = true;
        for (String s : samples) {
            if (s == null) return "text";
            if (!s.matches("-?\\d+(\\.\\d+)?")) allNumbers = false;
            if (!s.matches("\\d{4}-\\d{2}-\\d{2}")) allDates = false;
        }
        if (allNumbers) return "number";
        if (allDates) return "date";
        return "text";
    }
}
```

- [ ] **Step 6: Run the unit test**

Run: `./mvnw test "-Dtest=FacetTypeInferenceTest"`
Expected: PASS, 5 tests.

- [ ] **Step 7: Add the endpoint**

```java
package com.example.springbootrag.web;

import com.example.springbootrag.security.CurrentUser;
import com.example.springbootrag.service.ProjectService;
import com.example.springbootrag.understand.Facet;
import com.example.springbootrag.understand.FacetCatalogue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** What can be filtered on, derived from what is actually indexed. */
@RestController
public class FacetController {

    private final FacetCatalogue catalogue;
    private final ProjectService projectService;
    private final CurrentUser currentUser;

    public FacetController(FacetCatalogue catalogue, ProjectService projectService,
                           CurrentUser currentUser) {
        this.catalogue = catalogue;
        this.projectService = projectService;
        this.currentUser = currentUser;
    }

    @GetMapping("/projects/{projectId}/facets")
    public Map<String, Object> facets(@PathVariable long projectId,
                                      @RequestParam(required = false) String docType) {
        if (!projectService.exists(projectId)) {
            throw new IllegalArgumentException("project not found: " + projectId);
        }
        List<Facet> all = catalogue.forProjects(currentUser.context(), List.of(projectId));
        List<Facet> facets = docType == null || docType.isBlank()
                ? all
                : all.stream().filter(f -> docType.equals(f.docType())).toList();
        List<String> docTypes = all.stream().map(Facet::docType)
                .filter(java.util.Objects::nonNull).distinct().sorted().toList();
        return Map.of("docTypes", docTypes, "facets", facets);
    }
}
```

- [ ] **Step 8: Write the integration test**

Copy the container setup from `src/test/java/com/example/springbootrag/integration/RecordIngestIntegrationTest.java` (same images, same `FakeEmbeddingConfig`) and add:

```java
@Test
void facetsAreDerivedFromIndexedRecords() throws Exception {
    long projectId = projectRepository.create("facets-test", null);
    recordIngest.ingest(projectId, invoice("INV-1", "ACME Corp", 0.82));
    recordIngest.ingest(projectId, invoice("INV-2", "GLOBEX Ltd", 0.44));

    List<Facet> facets = catalogue.forProjects(TestContexts.PUBLIC, List.of(projectId));

    assertThat(facets).extracting(Facet::path)
            .contains("values.customer", "values.invoiceNumber", "conf.min");
    Facet customer = facets.stream().filter(f -> f.path().equals("values.customer"))
            .findFirst().orElseThrow();
    assertThat(customer.docType()).isEqualTo("invoice");
    assertThat(customer.samples()).contains("ACME Corp", "GLOBEX Ltd");
    assertThat(customer.distinctCount()).isEqualTo(2);

    Facet conf = facets.stream().filter(f -> f.path().equals("conf.min")).findFirst().orElseThrow();
    assertThat(conf.type()).isEqualTo("number");
}

@Test
void provenancePathsAreNotOffered() throws Exception {
    long projectId = projectRepository.create("facets-prov", null);
    recordIngest.ingest(projectId, invoice("INV-3", "ACME Corp", 0.82));

    assertThat(catalogue.forProjects(TestContexts.PUBLIC, List.of(projectId)))
            .extracting(Facet::path)
            .noneMatch(p -> p.startsWith("prov."));
}

@Test
void aCallerWithoutTheGroupSeesNoFacets() throws Exception {
    long projectId = projectRepository.create("facets-acl", null);
    recordIngest.ingest(projectId, invoice("INV-4", "ACME Corp", 0.82));

    SearchContext outsider = SearchContext.of("bob", java.util.Set.of("nobody"));
    assertThat(catalogue.forProjects(outsider, List.of(projectId))).isEmpty();
}
```

with the helper:

```java
private RecordRequest invoice(String docId, String customer, double confidence) throws Exception {
    String json = """
            {"invoiceNumber":"%s","issueDate":"2026-05-02",
             "customer":{"value":"%s","confidence":%s}}""".formatted(docId, customer, confidence);
    return new RecordRequest(docId, "invoice", new ObjectMapper().readTree(json),
            null, List.of("public"), null);
}
```

Note: each test creates its own project. Sharing one makes these tests lie to each other - the same trap `MetadataFilterIntegrationTest` hit.

- [ ] **Step 9: Run it**

Run: `./mvnw test "-Dtest=FacetCatalogueIntegrationTest"`
Expected: PASS, 3 tests.

- [ ] **Step 10: Full suite**

Run: `./mvnw test`
Expected: 280 existing tests still green plus 8 new.

---

## Task 2: ExtractionValidator

**Files:**
- Create: `src/main/java/com/example/springbootrag/understand/ExtractionValidator.java`
- Test: `src/test/java/com/example/springbootrag/understand/ExtractionValidatorTest.java`

**Interfaces:**
- Consumes: `Facet`, `MetadataFilter`, `UnderstandProperties`.
- Produces:
  - `record ExtractionValidator.Result(MetadataFilter filter, List<String> dropped)`
  - `static Result ExtractionValidator.validate(String modelJson, List<Facet> facets, int maxConditions, int maxValueLength)`

- [ ] **Step 1: Write the failing test**

```java
package com.example.springbootrag.understand;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractionValidatorTest {

    private static final List<Facet> FACETS = List.of(
            new Facet("invoice", "values.customer", "text", List.of("ACME Corp"), 3),
            new Facet("invoice", "values.total", "number", List.of("1899.5"), 9),
            new Facet("invoice", "values.issueDate", "date", List.of("2026-05-02"), 40),
            new Facet("delivery-note", "values.carrier", "text", List.of("Speedy Freight"), 2));

    private ExtractionValidator.Result validate(String json) {
        return ExtractionValidator.validate(json, FACETS, 4, 200);
    }

    @Test
    void keepsAConditionOnAKnownPath() {
        ExtractionValidator.Result r = validate("""
                {"docType":"invoice",
                 "filters":[{"path":"values.customer","op":"eq","value":"ACME Corp"}]}""");

        assertThat(r.filter().docType()).isEqualTo("invoice");
        assertThat(r.filter().conditions()).hasSize(1);
        assertThat(r.dropped()).isEmpty();
    }

    @Test
    void dropsAnInventedPath() {
        // The model hallucinating a field is the expected case, not the exceptional one.
        ExtractionValidator.Result r = validate("""
                {"filters":[{"path":"values.vendorName","op":"eq","value":"ACME"}]}""");

        assertThat(r.filter().conditions()).isEmpty();
        assertThat(r.dropped()).anyMatch(s -> s.contains("values.vendorName"));
    }

    @Test
    void dropsAnUnknownDocTypeButKeepsTheConditions() {
        ExtractionValidator.Result r = validate("""
                {"docType":"purchase-order",
                 "filters":[{"path":"values.customer","op":"eq","value":"ACME Corp"}]}""");

        assertThat(r.filter().docType()).isNull();
        assertThat(r.filter().conditions()).hasSize(1);
        assertThat(r.dropped()).anyMatch(s -> s.contains("purchase-order"));
    }

    @Test
    void appliesTheFacetTypeSoRangesCastCorrectly() {
        ExtractionValidator.Result r = validate("""
                {"filters":[{"path":"values.total","op":"range","gte":1000}]}""");

        assertThat(r.filter().conditions().get(0).type()).isEqualTo("number");
    }

    @Test
    void truncatesTooManyConditions() {
        ExtractionValidator.Result r = ExtractionValidator.validate("""
                {"filters":[{"path":"values.customer","op":"eq","value":"a"},
                            {"path":"values.customer","op":"eq","value":"b"},
                            {"path":"values.customer","op":"eq","value":"c"}]}""",
                FACETS, 2, 200);

        assertThat(r.filter().conditions()).hasSize(2);
        assertThat(r.dropped()).anyMatch(s -> s.contains("too many"));
    }

    @Test
    void dropsAnOversizedValue() {
        ExtractionValidator.Result r = validate("""
                {"filters":[{"path":"values.customer","op":"eq","value":"%s"}]}"""
                .formatted("x".repeat(500)));

        assertThat(r.filter().conditions()).isEmpty();
    }

    @Test
    void dropsAMalformedCondition() {
        // Unknown op, and a range with no bound - MetadataFilter.parse would throw on these, and
        // a model mistake must not become a 500.
        assertThat(validate("""
                {"filters":[{"path":"values.customer","op":"regex","value":"AC.*"}]}""")
                .filter().conditions()).isEmpty();
        assertThat(validate("""
                {"filters":[{"path":"values.total","op":"range"}]}""")
                .filter().conditions()).isEmpty();
    }

    @Test
    void unparseableOutputIsAnEmptyFilterNotAnError() {
        assertThat(validate("I think you want invoices for ACME").filter().isEmpty()).isTrue();
        assertThat(validate("").filter().isEmpty()).isTrue();
        assertThat(validate(null).filter().isEmpty()).isTrue();
    }

    @Test
    void findsJsonWrappedInProse() {
        ExtractionValidator.Result r = validate("""
                Sure! Here is the filter:
                {"docType":"invoice","filters":[]}
                Hope that helps.""");

        assertThat(r.filter().docType()).isEqualTo("invoice");
    }

    @Test
    void everythingDroppedMeansNoFilterNotAFilterMatchingNothing() {
        ExtractionValidator.Result r = validate("""
                {"filters":[{"path":"values.nope","op":"eq","value":"x"}]}""");

        // The standing trap (LEARNINGS section 13): "no filter" must render no predicate at all.
        assertThat(r.filter().isEmpty()).isTrue();
    }
}
```

- [ ] **Step 2: Run and watch fail**

Run: `./mvnw test "-Dtest=ExtractionValidatorTest"`
Expected: compile error - `ExtractionValidator` does not exist.

- [ ] **Step 3: Implement**

```java
package com.example.springbootrag.understand;

import com.example.springbootrag.repository.MetadataFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Model output is validated against the catalogue, never trusted.
 *
 * <p>Everything the extractor produces is rebuilt through {@link MetadataFilter#parse}, so it can
 * only ever express what the DSL already allows - and can never reach the access-label term. A
 * hallucinated path is the expected case, not the exceptional one, so it is dropped with a reason
 * rather than raised as an error.
 */
public final class ExtractionValidator {

    /** The surviving filter plus why anything was discarded (goes into the trace). */
    public record Result(MetadataFilter filter, List<String> dropped) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ExtractionValidator() {}

    public static Result validate(String modelJson, List<Facet> facets,
                                  int maxConditions, int maxValueLength) {
        List<String> dropped = new ArrayList<>();
        JsonNode root = parseLenient(modelJson);
        if (root == null || !root.isObject()) {
            return new Result(MetadataFilter.none(), List.of("model output was not JSON"));
        }

        Map<String, String> typeByPath = new HashMap<>();
        Set<String> knownDocTypes = new java.util.HashSet<>();
        for (Facet f : facets) {
            typeByPath.put(f.path(), f.type());
            if (f.docType() != null) knownDocTypes.add(f.docType());
        }

        String docType = root.hasNonNull("docType") ? root.get("docType").asText() : null;
        if (docType != null && !knownDocTypes.contains(docType)) {
            dropped.add("unknown docType '" + docType + "'");
            docType = null;
        }

        // Rebuild a clean filters array, then let MetadataFilter.parse do the real validation.
        ObjectNode clean = MAPPER.createObjectNode();
        if (docType != null) clean.put("docType", docType);
        var array = clean.putArray("filters");

        JsonNode filters = root.get("filters");
        if (filters != null && filters.isArray()) {
            for (JsonNode f : filters) {
                if (array.size() >= maxConditions) {
                    dropped.add("too many conditions, kept the first " + maxConditions);
                    break;
                }
                String path = f.hasNonNull("path") ? f.get("path").asText() : null;
                if (path == null || !typeByPath.containsKey(path)) {
                    dropped.add("unknown path '" + path + "'");
                    continue;
                }
                if (tooLong(f, maxValueLength)) {
                    dropped.add("value too long for '" + path + "'");
                    continue;
                }
                ObjectNode condition = f.deepCopy();
                // The facet type decides the cast, not the model: it is derived from the data.
                condition.put("type", typeByPath.get(path));
                array.add(condition);
                if (!parses(clean)) {
                    array.remove(array.size() - 1);
                    dropped.add("malformed condition on '" + path + "'");
                }
            }
        }

        MetadataFilter filter = parses(clean) ? MetadataFilter.parse(clean.toString())
                                              : MetadataFilter.none();
        return new Result(filter, List.copyOf(dropped));
    }

    private static boolean parses(JsonNode candidate) {
        try {
            MetadataFilter.parse(candidate.toString());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean tooLong(JsonNode f, int max) {
        for (String key : List.of("value", "gte", "gt", "lte", "lt")) {
            JsonNode v = f.get(key);
            if (v != null && v.isTextual() && v.asText().length() > max) return true;
        }
        JsonNode values = f.get("values");
        if (values != null && values.isArray()) {
            for (JsonNode v : values) {
                if (v.isTextual() && v.asText().length() > max) return true;
            }
        }
        return false;
    }

    /** Models wrap JSON in prose; take the first balanced object. */
    static JsonNode parseLenient(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String text = raw.trim();
        int start = text.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) {
                try {
                    return MAPPER.readTree(text.substring(start, i + 1));
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }
}
```

- [ ] **Step 4: Run the test**

Run: `./mvnw test "-Dtest=ExtractionValidatorTest"`
Expected: PASS, 10 tests.

---

## Task 3: QueryUnderstanding

**Files:**
- Create: `src/main/java/com/example/springbootrag/understand/QueryUnderstanding.java`
- Modify: `src/main/java/com/example/springbootrag/chat/ChatProvider.java`
- Modify: `src/main/java/com/example/springbootrag/chat/OllamaChatProvider.java:50-60`
- Test: `src/test/java/com/example/springbootrag/understand/QueryUnderstandingTest.java`
- Test: `src/test/java/com/example/springbootrag/chat/OllamaChatProviderTest.java`

**Interfaces:**
- Consumes: `ChatProvider.chat(String systemPrompt, String userPrompt)`, `FacetCatalogue.forProjects`, `ExtractionValidator.validate`, `UnderstandProperties`, `ChatProperties.getModel()`.
- Produces (new provider overload): `ChatProvider.chat(String systemPrompt, String userPrompt, String model)` - default implementation ignores `model` and delegates to the 2-arg `chat`.

**Why the provider changes:** `OllamaChatProvider` hardcodes `props.getModel()` in its request body, so
`app.understand.model` would be read and then silently ignored - a config knob that does nothing is
worse than no knob. The overload is the smallest change that makes model tiering real, and its
default keeps every other provider implementation valid.
- Produces:
  - `record QueryUnderstanding.Extraction(MetadataFilter filter, long latencyMs, List<String> dropped)` with `static Extraction none()`
  - `Extraction QueryUnderstanding.extract(SearchContext ctx, List<Long> projectIds, String question)`
  - `static String QueryUnderstanding.buildPrompt(List<Facet> facets)` - package-visible for the test

- [ ] **Step 1: Write the failing test**

```java
package com.example.springbootrag.understand;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.config.UnderstandProperties;
import com.example.springbootrag.repository.FacetRepository;
import com.example.springbootrag.security.TestContexts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QueryUnderstandingTest {

    private static final List<Facet> FACETS = List.of(
            new Facet("invoice", "values.customer", "text", List.of("ACME Corp"), 3),
            new Facet("invoice", "values.total", "number", List.of("1899.5"), 9));

    /** Returns whatever the test tells it to, and records the prompt it was given. */
    private static class StubChat implements ChatProvider {
        String lastSystem;
        String lastUser;
        Function<String, String> reply = q -> "{}";
        RuntimeException boom;

        @Override public String chat(String systemPrompt, String userPrompt) {
            lastSystem = systemPrompt;
            lastUser = userPrompt;
            if (boom != null) throw boom;
            return reply.apply(userPrompt);
        }
    }

    private QueryUnderstanding service(StubChat chat, boolean enabled) {
        FacetRepository repo = mock(FacetRepository.class);
        when(repo.facets(any(), anyList(), anyInt())).thenReturn(FACETS);
        UnderstandProperties props = new UnderstandProperties();
        props.setEnabled(enabled);
        var catalogue = new FacetCatalogue(repo, props);
        var chatProps = new com.example.springbootrag.config.ChatProperties();
        return new QueryUnderstanding(chat, catalogue, props, chatProps);
    }

    @Test
    void extractsAFilterFromTheModelReply() {
        StubChat chat = new StubChat();
        chat.reply = q -> """
                {"docType":"invoice",
                 "filters":[{"path":"values.customer","op":"eq","value":"ACME Corp"}]}""";

        var extraction = service(chat, true).extract(TestContexts.PUBLIC, List.of(1L),
                "unpaid ACME Corp invoices");

        assertThat(extraction.filter().docType()).isEqualTo("invoice");
        assertThat(extraction.filter().conditions()).hasSize(1);
    }

    @Test
    void thePromptCarriesTheFacetsAndTheQuestion() {
        StubChat chat = new StubChat();
        service(chat, true).extract(TestContexts.PUBLIC, List.of(1L), "invoices over 1000");

        assertThat(chat.lastSystem).contains("values.customer").contains("values.total")
                .contains("number");
        assertThat(chat.lastUser).contains("invoices over 1000");
    }

    @Test
    void disabledMeansNoModelCallAndNoFilter() {
        StubChat chat = new StubChat();
        var extraction = service(chat, false).extract(TestContexts.PUBLIC, List.of(1L), "anything");

        assertThat(extraction.filter().isEmpty()).isTrue();
        assertThat(chat.lastUser).isNull();
    }

    @Test
    void aModelFailureIsNotARequestFailure() {
        StubChat chat = new StubChat();
        chat.boom = new IllegalStateException("ollama down");

        var extraction = service(chat, true).extract(TestContexts.PUBLIC, List.of(1L), "anything");

        assertThat(extraction.filter().isEmpty()).isTrue();
        assertThat(extraction.dropped()).anyMatch(s -> s.contains("extraction failed"));
    }

    @Test
    void anEmptyCatalogueSkipsTheModelEntirely() {
        // Nothing to filter on means nothing to extract - do not pay for a model call.
        FacetRepository empty = mock(FacetRepository.class);
        when(empty.facets(any(), anyList(), anyInt())).thenReturn(List.of());
        UnderstandProperties props = new UnderstandProperties();
        StubChat chat = new StubChat();
        var service = new QueryUnderstanding(chat, new FacetCatalogue(empty, props), props,
                new com.example.springbootrag.config.ChatProperties());

        var extraction = service.extract(TestContexts.PUBLIC, List.of(1L), "anything");

        assertThat(extraction.filter().isEmpty()).isTrue();
        assertThat(chat.lastUser).isNull();
    }

    @Test
    void latencyIsRecorded() {
        StubChat chat = new StubChat();
        var extraction = service(chat, true).extract(TestContexts.PUBLIC, List.of(1L), "q");

        assertThat(extraction.latencyMs()).isGreaterThanOrEqualTo(0);
    }
}
```

- [ ] **Step 2: Run and watch fail**

Run: `./mvnw test "-Dtest=QueryUnderstandingTest"`
Expected: compile error - `QueryUnderstanding` does not exist.

- [ ] **Step 3: Add the model override to the provider**

In `ChatProvider`:

```java
    /**
     * Same as {@link #chat}, but against a named model.
     *
     * <p>Exists so a cheap fast model can do query understanding while the large one writes the
     * answer - the model-tiering lever in RAG-MASTERY section 8. The default ignores the name, so a
     * provider that cannot switch models per call stays valid and simply uses its configured one.
     */
    default String chat(String systemPrompt, String userPrompt, String model) {
        return chat(systemPrompt, userPrompt);
    }
```

In `OllamaChatProvider`, extract the existing body-building so the model is a parameter, and have
the 2-arg path pass `props.getModel()`:

```java
    @Override
    public String chat(String systemPrompt, String userPrompt, String model) {
        return chatDetailed(systemPrompt, userPrompt,
                model == null || model.isBlank() ? props.getModel() : model).content();
    }
```

with `chatDetailed(String, String, String model)` doing what `chatDetailed(String, String)` does
today, putting `model` into the request map instead of `props.getModel()`, and the existing 2-arg
`chatDetailed` delegating with `props.getModel()`. `think` stays `true` - qwen3 leaks tag-less
reasoning into content otherwise (`LEARNINGS.md` section 12).

Add to `OllamaChatProviderTest`, using the existing MockWebServer setup:

```java
@Test
void anExplicitModelOverridesTheConfiguredOne() throws Exception {
    server.enqueue(new MockResponse()
            .setBody("{\"message\":{\"content\":\"{}\"},\"done\":true}")
            .addHeader("Content-Type", "application/json"));

    provider.chat("system", "user", "qwen3:1.7b");

    String body = server.takeRequest().getBody().readUtf8();
    assertThat(body).contains("\"model\":\"qwen3:1.7b\"");
}

@Test
void aBlankModelFallsBackToTheConfiguredOne() throws Exception {
    server.enqueue(new MockResponse()
            .setBody("{\"message\":{\"content\":\"{}\"},\"done\":true}")
            .addHeader("Content-Type", "application/json"));

    provider.chat("system", "user", "");

    assertThat(server.takeRequest().getBody().readUtf8()).contains("\"model\":\"qwen3:8b\"");
}
```

Match the existing test's MockWebServer API style rather than the snippet above if it differs -
read the top of `OllamaChatProviderTest` first.

- [ ] **Step 4: Implement QueryUnderstanding**

```java
package com.example.springbootrag.understand;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.config.ChatProperties;
import com.example.springbootrag.config.UnderstandProperties;
import com.example.springbootrag.repository.MetadataFilter;
import com.example.springbootrag.security.SearchContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Turns a question into a metadata filter with one LLM call.
 *
 * <p>Two rules keep this safe on the answer path: it never throws, and its output is validated
 * against the facet catalogue rather than trusted. An answer that would have worked must not fail
 * because query understanding did.
 */
@Service
public class QueryUnderstanding {

    private static final Logger log = LoggerFactory.getLogger(QueryUnderstanding.class);

    /** What extraction produced, plus what it cost and what was thrown away. */
    public record Extraction(MetadataFilter filter, long latencyMs, List<String> dropped) {
        public static Extraction none() {
            return new Extraction(MetadataFilter.none(), 0L, List.of());
        }
    }

    private final ChatProvider chat;
    private final FacetCatalogue catalogue;
    private final UnderstandProperties props;
    private final ChatProperties chatProps;

    public QueryUnderstanding(ChatProvider chat, FacetCatalogue catalogue,
                              UnderstandProperties props, ChatProperties chatProps) {
        this.chat = chat;
        this.catalogue = catalogue;
        this.props = props;
        this.chatProps = chatProps;
    }

    public Extraction extract(SearchContext ctx, List<Long> projectIds, String question) {
        if (!props.isEnabled() || question == null || question.isBlank()) {
            return Extraction.none();
        }
        List<Facet> facets = catalogue.forProjects(ctx, projectIds);
        if (facets.isEmpty()) {
            return Extraction.none();   // nothing to filter on: do not pay for a model call
        }
        long start = System.nanoTime();
        try {
            String reply = chat.chat(buildPrompt(facets), question, model());
            ExtractionValidator.Result result = ExtractionValidator.validate(
                    reply, facets, props.getMaxConditions(), props.getMaxValueLength());
            return new Extraction(result.filter(), msSince(start), result.dropped());
        } catch (RuntimeException e) {
            log.warn("query understanding failed; continuing unfiltered", e);
            return new Extraction(MetadataFilter.none(), msSince(start),
                    List.of("extraction failed: " + e.getClass().getSimpleName()));
        }
    }

    /** Which model does the extraction - empty config means the answer model. */
    public String model() {
        return props.getModel() == null || props.getModel().isBlank()
                ? chatProps.getModel() : props.getModel();
    }

    static String buildPrompt(List<Facet> facets) {
        StringBuilder sb = new StringBuilder("""
                You convert a user's question into a search filter. Reply with JSON only, no prose.

                Shape:
                {"docType": "<one of the document types, or omit>",
                 "filters": [{"path": "<one of the paths below>", "op": "eq|in|range|exists",
                              "value": "...", "values": [...],
                              "gte": ..., "gt": ..., "lte": ..., "lt": ...}]}

                Rules:
                - Use ONLY the paths listed below. Never invent one.
                - Omit a filter you are not confident about. An empty filters list is a valid answer
                  and is better than a wrong guess.
                - Match sample values exactly when the question names one.
                - Do not put the free-text part of the question into a filter; it is searched
                  separately.

                Available document types and paths:
                """);
        for (Facet f : facets) {
            sb.append("- ").append(f.docType() == null ? "(any)" : f.docType())
              .append(" | ").append(f.path())
              .append(" | ").append(f.type())
              .append(" | examples: ").append(String.join(", ", f.samples()))
              .append('\n');
        }
        return sb.toString();
    }

    private static long msSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `./mvnw test "-Dtest=QueryUnderstandingTest,OllamaChatProviderTest"`
Expected: PASS - 6 new understanding tests plus the existing provider tests and the 2 new ones.

Note: `StubChat` in `QueryUnderstandingTest` implements only the 2-arg `chat`, so the 3-arg default
delegates to it - which is exactly the behaviour a provider without model switching should have.

---

## Task 4: Trace the applied filter

**Files:**
- Modify: `src/main/resources/schema.sql`
- Modify: `src/main/java/com/example/springbootrag/trace/RagTrace.java`
- Modify: `src/main/java/com/example/springbootrag/trace/TraceRepository.java:34-94`
- Modify: `src/main/java/com/example/springbootrag/trace/TraceRecorder.java:46-70`
- Modify: `src/test/java/com/example/springbootrag/integration/TraceRepositoryIntegrationTest.java`
- Modify: `src/test/java/com/example/springbootrag/trace/NoopTraceRecorder.java` (if it overrides `record`)

**Interfaces:**
- Consumes: `MetadataFilter`.
- Produces:
  - `RagTrace` gains two trailing components: `String appliedFilter` (JSON text or null), `boolean filterWidened`.
  - `TraceRecorder.record(..., String guardReason, String appliedFilter, boolean filterWidened)` - the existing 12-arg overload stays and delegates with `(null, false)`.

- [ ] **Step 1: Write the failing test**

Add to `TraceRepositoryIntegrationTest`:

```java
@Test
void appliedFilterAndWidenFlagRoundTrip() {
    UUID id = UUID.randomUUID();
    repo.insert(new RagTrace(id, Instant.now(), "alice", List.of(1L), "unpaid ACME invoices",
            null, "rerank", List.of(), Map.of("understand", 900L, "retrieve", 12L),
            null, null, "answer", null,
            "{\"docType\":\"invoice\"}", true));

    RagTrace back = repo.recent("alice", 10).stream()
            .filter(t -> t.requestId().equals(id)).findFirst().orElseThrow();

    assertThat(back.appliedFilter()).contains("invoice");
    assertThat(back.filterWidened()).isTrue();
    assertThat(back.stageLatencyMs()).containsEntry("understand", 900L);
}
```

- [ ] **Step 2: Run and watch fail**

Run: `./mvnw test "-Dtest=TraceRepositoryIntegrationTest"`
Expected: compile error - `RagTrace` takes 13 components, not 15.

- [ ] **Step 3: Add the columns**

Append to `src/main/resources/schema.sql`:

```sql
-- ---- Query understanding (2026-08-06) ----
-- The filter that was actually applied and whether it had to be dropped. Without these, the one
-- question a surprised user asks - "why did it not find my document?" - has no answer.
ALTER TABLE rag_trace ADD COLUMN IF NOT EXISTS applied_filter JSONB;
ALTER TABLE rag_trace ADD COLUMN IF NOT EXISTS filter_widened BOOLEAN NOT NULL DEFAULT false;
```

- [ ] **Step 4: Extend the record, repository, and recorder**

`RagTrace`: add `String appliedFilter, boolean filterWidened` as the last two components, with javadoc:

```java
 * @param appliedFilter  the metadata filter that was actually used, as JSON - null when none was
 * @param filterWidened  true when the filter matched nothing and retrieval was retried without it
```

`TraceRepository.insert`: add both columns to the INSERT (`?::jsonb` for `applied_filter`), and both to the SELECT lists and `mapRow`:

```java
                t.appliedFilter(),
                t.filterWidened());
```
```java
                rs.getString("applied_filter"),
                rs.getBoolean("filter_widened"));
```

`TraceRecorder`: keep the existing 12-arg `record(...)` delegating with `(null, false)` and add the 14-arg version that passes both through to the `RagTrace` constructor.

- [ ] **Step 5: Run the test**

Run: `./mvnw test "-Dtest=TraceRepositoryIntegrationTest"`
Expected: PASS.

- [ ] **Step 6: Full suite**

Run: `./mvnw test`
Expected: green - `TraceControllerTest` and `AskServiceTest` construct `RagTrace`/mock `TraceRecorder`, so watch those two first.

---

## Task 5: Wire into /ask, with widening

**Files:**
- Modify: `src/main/java/com/example/springbootrag/service/AskService.java`
- Modify: `src/main/java/com/example/springbootrag/web/dto/AskResponse.java`
- Test: `src/test/java/com/example/springbootrag/integration/QueryUnderstandingIntegrationTest.java`

**Interfaces:**
- Consumes: `QueryUnderstanding.extract`, `TraceRecorder.record(...14-arg)`.
- Produces: `AskResponse(String answer, List<Source> sources, Object appliedFilter, boolean widened)`.

- [ ] **Step 1: Write the failing integration test**

Container setup as in `RecordIngestIntegrationTest`, plus a stub `ChatProvider` whose reply depends on the system prompt (extraction prompt vs answer prompt):

```java
@TestConfiguration
static class StubChatConfig {
    static volatile String extractionReply = "{}";
    static volatile boolean throwOnExtraction = false;

    @Bean @Primary
    ChatProvider stubChat() {
        return new ChatProvider() {
            @Override public String chat(String systemPrompt, String userPrompt) {
                if (systemPrompt.contains("convert a user's question into a search filter")) {
                    if (throwOnExtraction) throw new IllegalStateException("model down");
                    return extractionReply;
                }
                return "The answer is here [1]";
            }
        };
    }
}
```

```java
@Test
void anExtractedFilterNarrowsTheAnswerSources() {
    long projectId = seedTwoCustomers();
    StubChatConfig.extractionReply = """
            {"docType":"invoice",
             "filters":[{"path":"values.customer","op":"eq","value":"ACME Corp"}]}""";

    AskResponse res = askService.ask(TestContexts.PUBLIC, "late payment", List.of(projectId));

    assertThat(res.sources()).isNotEmpty();
    assertThat(res.sources()).allMatch(s -> s.docId().equals("INV-ACME"));
    assertThat(res.widened()).isFalse();
    assertThat(res.appliedFilter()).isNotNull();
}

@Test
void aFilterThatMatchesNothingWidensAndSaysSo() {
    long projectId = seedTwoCustomers();
    StubChatConfig.extractionReply = """
            {"filters":[{"path":"values.customer","op":"eq","value":"NOBODY Ltd"}]}""";

    AskResponse res = askService.ask(TestContexts.PUBLIC, "late payment", List.of(projectId));

    // A mis-extracted value must not become a confident "I don't know".
    assertThat(res.widened()).isTrue();
    assertThat(res.sources()).isNotEmpty();
}

@Test
void extractionFailureLeavesTheAnswerWorking() {
    long projectId = seedTwoCustomers();
    StubChatConfig.throwOnExtraction = true;
    try {
        AskResponse res = askService.ask(TestContexts.PUBLIC, "late payment", List.of(projectId));
        assertThat(res.sources()).isNotEmpty();
        assertThat(res.appliedFilter()).isNull();
    } finally {
        StubChatConfig.throwOnExtraction = false;
    }
}

@Test
void anExplicitCallerFilterWinsOverExtraction() {
    long projectId = seedTwoCustomers();
    StubChatConfig.extractionReply = """
            {"filters":[{"path":"values.customer","op":"eq","value":"ACME Corp"}]}""";

    MetadataFilter caller = MetadataFilter.parse("""
            {"filters":[{"path":"values.customer","op":"eq","value":"GLOBEX Ltd"}]}""");
    AskResponse res = askService.ask(TestContexts.PUBLIC, "late payment", List.of(projectId), caller);

    assertThat(res.sources()).allMatch(s -> s.docId().equals("INV-GLOBEX"));
}

@Test
void theTraceCarriesTheFilterAndTheWidenDecision() {
    long projectId = seedTwoCustomers();
    StubChatConfig.extractionReply = """
            {"filters":[{"path":"values.customer","op":"eq","value":"NOBODY Ltd"}]}""";

    askService.ask(TestContexts.PUBLIC, "late payment", List.of(projectId));

    RagTrace trace = traceRepository.recent("public-user", 1).get(0);
    assertThat(trace.filterWidened()).isTrue();
    assertThat(trace.appliedFilter()).contains("NOBODY Ltd");
    assertThat(trace.stageLatencyMs()).containsKey("understand");
}
```

`seedTwoCustomers()` creates a project and ingests `INV-ACME` (customer "ACME Corp") and
`INV-GLOBEX` (customer "GLOBEX Ltd"), both with the body text "payment is late".
Use `TestContexts.PUBLIC.principal()` for the trace lookup rather than a hard-coded name.

- [ ] **Step 2: Run and watch fail**

Run: `./mvnw test "-Dtest=QueryUnderstandingIntegrationTest"`
Expected: compile error - `AskResponse` has no `widened()`.

- [ ] **Step 3: Extend AskResponse**

```java
package com.example.springbootrag.web.dto;

import java.util.List;

/**
 * RAG answer plus the chunks it was generated from.
 *
 * @param appliedFilter the metadata filter actually used, in the same shape the API accepts, so a
 *                      client can echo it straight back as an explicit filter. Null when none.
 * @param widened       true when that filter matched nothing and retrieval was retried without it
 */
public record AskResponse(String answer, List<Source> sources,
                          Object appliedFilter, boolean widened) {

    /** Pre-filter callers: no filter, not widened. */
    public AskResponse(String answer, List<Source> sources) {
        this(answer, sources, null, false);
    }

    public record Source(int index, String docId, String headingPath, double score, String content, int chunkIndex) {}
}
```

- [ ] **Step 4: Wire AskService**

In the 4-arg `ask(ctx, question, projectIds, callerFilter)`:

```java
        // Extraction runs on the raw question. An explicit caller filter wins outright - merging
        // the two would let a model silently narrow a scope the caller deliberately set.
        QueryUnderstanding.Extraction extraction = callerFilter == null || callerFilter.isEmpty()
                ? understanding.extract(ctx, projectIds, question)
                : QueryUnderstanding.Extraction.none();
        MetadataFilter filter = callerFilter != null && !callerFilter.isEmpty()
                ? callerFilter : extraction.filter();

        SearchService.TracedSearch search = searchService.searchTraced(ctx, "rerank", question,
                props.getContextChunks(), projectIds, List.of(), filter);
        boolean widened = false;
        if (search.hits().isEmpty() && !filter.isEmpty()) {
            // A wrong filter must cost one extra query, not a confident refusal.
            search = searchService.searchTraced(ctx, "rerank", question,
                    props.getContextChunks(), projectIds, List.of(), MetadataFilter.none());
            widened = true;
        }
```

Put `extraction.latencyMs()` into the stages map as `understand`, pass
`filter.isEmpty() ? null : filterJson(filter)` and `widened` to the 14-arg `tracer.record(...)`,
and return them on the `AskResponse`. `filterJson` is a small private helper using the injected
`ObjectMapper`; on serialisation failure it returns null, because a trace must never break an
answer.

- [ ] **Step 5: Run the tests**

Run: `./mvnw test "-Dtest=QueryUnderstandingIntegrationTest"`
Expected: PASS, 5 tests.

- [ ] **Step 6: Full suite**

Run: `./mvnw test`
Expected: green. `AskServiceTest` constructs `AskService` directly, so it needs the new constructor argument.

---

## Task 6: Wire into /chat/stream

**Files:**
- Modify: `src/main/java/com/example/springbootrag/service/ChatService.java`
- Modify: `src/main/java/com/example/springbootrag/web/ChatController.java`
- Modify: `src/test/java/com/example/springbootrag/service/ChatServiceTest.java`
- Modify: `src/test/java/com/example/springbootrag/web/ChatControllerTest.java`

**Interfaces:**
- Consumes: `QueryUnderstanding.extract`, the widening logic from Task 5.
- Produces: `ChatService.StreamOutcome` gains `Object appliedFilter` and `boolean widened`; a `filter` NDJSON frame.

- [ ] **Step 1: Write the failing test**

Add to `ChatServiceTest` (stub ChatProvider already exists there):

```java
@Test
void extractionUsesTheRawQuestionNotTheCondensedOne() {
    // Condensation rewrites pronouns but can drop the entity the filter needs.
    when(searchService.searchTraced(any(SearchContext.class), eq("rerank"),
            eq("condensed standalone query"), anyInt(), anyList(), any(), any(MetadataFilter.class)))
            .thenReturn(new SearchService.TracedSearch(
                    List.of(new SearchHit(1, "d", 0, "c", null, null, 0.5, null)),
                    Map.of("embed", 1L, "retrieve", 2L)));

    service.chatStream(TestContexts.PUBLIC, List.of(
            new ChatMessage("user", "what about ACME Corp?"),
            new ChatMessage("assistant", "..."),
            new ChatMessage("user", "and their unpaid ones?")), List.of(), List.of(), t -> {});

    verify(understanding).extract(any(), anyList(), eq("and their unpaid ones?"));
}
```

And to `ChatControllerTest`:

```java
@Test
void aFilterFrameIsEmittedBeforeTheTokens() throws Exception {
    when(chatService.chatStream(any(), anyList(), anyList(), any(), anyBoolean(), any(), any(), any()))
            .thenAnswer(inv -> {
                Consumer<String> onToken = inv.getArgument(6);
                onToken.accept("Hi");
                return new ChatService.StreamOutcome(List.of(),
                        new AnswerGuard.Verdict(true, "cited", "Hi"), java.util.UUID.randomUUID(),
                        java.util.Map.of("docType", "invoice"), true);
            });

    MvcResult started = mvc.perform(post("/chat/stream").contentType("application/json")
                    .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
            .andExpect(request().asyncStarted()).andReturn();
    String body = mvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString();

    assertThat(body).contains("\"type\":\"filter\"").contains("\"widened\":true");
    assertThat(body.indexOf("\"type\":\"filter\"")).isLessThan(body.indexOf("\"type\":\"token\""));
}
```

- [ ] **Step 2: Run and watch fail**

Run: `./mvnw test "-Dtest=ChatServiceTest,ChatControllerTest"`
Expected: FAIL - `StreamOutcome` has three components, and no `understanding` field exists on `ChatService`.

- [ ] **Step 3: Implement**

`ChatService`: inject `QueryUnderstanding`, and inside the 8-arg `chatStream`, after `retrievalQuery` is computed:

```java
        // Extraction reads the RAW question; condensation is for retrieval wording, and it can
        // drop the entity the filter needs.
        QueryUnderstanding.Extraction extraction = filter == null || filter.isEmpty()
                ? understanding.extract(ctx, pScope, last.content())
                : QueryUnderstanding.Extraction.none();
        MetadataFilter effective = filter != null && !filter.isEmpty() ? filter : extraction.filter();
```

then retrieve with `effective`, apply the same widen-on-empty retry as Task 5, add `understand` to
the stages map, extend `StreamOutcome` with `appliedFilter` and `widened`, and pass both to the
14-arg `tracer.record(...)`.

`ChatController`: after resolving the outcome is too late - the frame must precede the tokens, so
emit it from inside the service via a new `Consumer<Map<String,Object>> onFilter` callback, or
simplest: have `ChatService` call `onToken`'s sibling. Use an explicit callback parameter:

```java
    public StreamOutcome chatStream(SearchContext ctx, List<ChatMessage> history,
                                    List<Long> projectIds, List<String> docIds, boolean think,
                                    MetadataFilter filter,
                                    Consumer<Map<String, Object>> onFilter,
                                    Consumer<String> onToken, Consumer<String> onReasoning)
```

with the existing 8-arg overload delegating and passing `f -> {}`. The controller passes
`f -> writeFrame(out, Map.of("type", "filter", "applied", f.get("applied"), "widened", f.get("widened")))`.

- [ ] **Step 4: Run the tests**

Run: `./mvnw test "-Dtest=ChatServiceTest,ChatControllerTest"`
Expected: PASS.

- [ ] **Step 5: Full suite**

Run: `./mvnw test`
Expected: green.

---

## Task 7: Synthetic record corpus and golden set

**Files:**
- Create: `src/test/java/com/example/springbootrag/eval/RecordCorpus.java`
- Create: `src/test/java/com/example/springbootrag/eval/RecordGoldenEntry.java`
- Create: `src/test/java/com/example/springbootrag/eval/RecordGoldenSet.java`
- Create: `src/test/resources/eval/records-golden.yaml`
- Test: `src/test/java/com/example/springbootrag/eval/RecordCorpusTest.java`

**Interfaces:**
- Consumes: `RecordRequest`.
- Produces:
  - `static List<RecordRequest> RecordCorpus.generate(long seed)` - 210 records, deterministic
  - `record RecordGoldenEntry(String question, String expectedDocType, List<Map<String,Object>> expectedFilters, List<String> expectedDocIds, boolean expectNoFilter, boolean expectWiden)`
  - `static List<RecordGoldenEntry> RecordGoldenSet.load()` - reads `/eval/records-golden.yaml`

- [ ] **Step 1: Write the failing corpus test**

```java
package com.example.springbootrag.eval;

import com.example.springbootrag.web.dto.RecordRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecordCorpusTest {

    @Test
    void generationIsDeterministic() {
        // The whole point of a committed corpus: identical on every machine and in CI.
        assertThat(RecordCorpus.generate(42)).usingRecursiveComparison()
                .isEqualTo(RecordCorpus.generate(42));
    }

    @Test
    void coversThreeDocumentTypesWithDifferentSchemas() {
        List<RecordRequest> records = RecordCorpus.generate(42);

        assertThat(records).hasSize(210);
        assertThat(records).extracting(RecordRequest::docType).distinct()
                .containsExactlyInAnyOrder("invoice", "delivery-note", "contract");
        assertThat(records).extracting(RecordRequest::docId).doesNotHaveDuplicates();
    }

    @Test
    void everyRecordCarriesAtLeastOneWrappedFieldWithConfidence() {
        assertThat(RecordCorpus.generate(42)).allSatisfy(r ->
                assertThat(r.record().toString()).contains("confidence"));
    }

    @Test
    void goldenSetLoadsAndEveryEntryIsAnswerable() {
        List<RecordGoldenEntry> golden = RecordGoldenSet.load();

        assertThat(golden).hasSizeGreaterThanOrEqualTo(15);
        assertThat(golden).allSatisfy(e -> assertThat(e.question()).isNotBlank());
        // The two cases that keep the design honest.
        assertThat(golden).anyMatch(RecordGoldenEntry::expectNoFilter);
        assertThat(golden).anyMatch(RecordGoldenEntry::expectWiden);
    }
}
```

- [ ] **Step 2: Run and watch fail**

Run: `./mvnw test "-Dtest=RecordCorpusTest"`
Expected: compile error - `RecordCorpus` does not exist.

- [ ] **Step 3: Implement the generator**

Fixed vocabularies are what make expected results computable rather than eyeballed:

```java
package com.example.springbootrag.eval;

import com.example.springbootrag.web.dto.RecordRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** A deterministic synthetic record corpus: same bytes on every machine, so CI can run the eval. */
public final class RecordCorpus {

    private static final ObjectMapper M = new ObjectMapper();

    static final List<String> CUSTOMERS =
            List.of("ACME Corp", "GLOBEX Ltd", "Initech", "Umbrella SA", "Soylent BV");
    static final List<String> STATUSES = List.of("open", "overdue", "paid");
    static final List<String> CARRIERS = List.of("Speedy Freight", "NordCargo", "AirLift");
    static final List<String> PARTIES = List.of("ACME Corp", "Initech", "Umbrella SA");

    private RecordCorpus() {}

    public static List<RecordRequest> generate(long seed) {
        Random rnd = new Random(seed);
        List<RecordRequest> out = new ArrayList<>(210);
        for (int i = 0; i < 120; i++) out.add(invoice(i, rnd));
        for (int i = 0; i < 60; i++) out.add(deliveryNote(i, rnd));
        for (int i = 0; i < 30; i++) out.add(contract(i, rnd));
        return out;
    }

    private static RecordRequest invoice(int i, Random rnd) {
        String customer = CUSTOMERS.get(rnd.nextInt(CUSTOMERS.size()));
        String status = STATUSES.get(rnd.nextInt(STATUSES.size()));
        int month = 1 + rnd.nextInt(12);
        double total = 100 + rnd.nextInt(9900) + 0.5;
        double conf = 0.4 + rnd.nextInt(60) / 100.0;
        String json = """
                {"invoiceNumber":"INV-%04d",
                 "issueDate":"2026-%02d-15",
                 "status":"%s",
                 "total":%s,
                 "customer":{"value":"%s","confidence":%s,"grounding":{"page":1,"bbox":[10,20,30,40]}},
                 "notes":"%s",
                 "lineItems":[{"sku":"SKU-%03d","description":"consulting hours","amount":%s}]}
                """.formatted(i, month, status, total, customer, conf,
                status.equals("overdue") ? "payment is late, reminder sent" : "payment received on time",
                i % 50, total);
        return request("INV-%04d".formatted(i), "invoice", json);
    }

    private static RecordRequest deliveryNote(int i, Random rnd) {
        String carrier = CARRIERS.get(rnd.nextInt(CARRIERS.size()));
        int month = 1 + rnd.nextInt(12);
        String json = """
                {"shipmentId":"DN-%04d",
                 "deliveredOn":"2026-%02d-08",
                 "carrier":{"value":"%s","confidence":0.9},
                 "remarks":"goods delivered in full",
                 "packages":[{"trackingId":"TRK-%04d","weightKg":%s,"contents":"spare parts"}]}
                """.formatted(i, month, carrier, i, 1 + rnd.nextInt(40));
        return request("DN-%04d".formatted(i), "delivery-note", json);
    }

    private static RecordRequest contract(int i, Random rnd) {
        String party = PARTIES.get(rnd.nextInt(PARTIES.size()));
        int months = 12 * (1 + rnd.nextInt(3));
        String json = """
                {"contractId":"CT-%04d",
                 "effectiveDate":"2026-%02d-01",
                 "termMonths":%d,
                 "value":%d,
                 "party":{"value":"%s","confidence":0.88},
                 "summary":"annual support and maintenance agreement"}
                """.formatted(i, 1 + rnd.nextInt(12), months, 10000 + i * 500, party);
        return request("CT-%04d".formatted(i), "contract", json);
    }

    private static RecordRequest request(String docId, String docType, String json) {
        try {
            return new RecordRequest(docId, docType, M.readTree(json), null, List.of("public"), null);
        } catch (Exception e) {
            throw new IllegalStateException("bad corpus record " + docId, e);
        }
    }
}
```

- [ ] **Step 4: Write the golden set**

`src/test/resources/eval/records-golden.yaml`:

```yaml
- question: "invoices for ACME Corp"
  expectedDocType: invoice
  expectedFilters:
    - {path: values.customer, op: eq, value: "ACME Corp"}

- question: "unpaid invoices for GLOBEX Ltd"
  expectedDocType: invoice
  expectedFilters:
    - {path: values.customer, op: eq, value: "GLOBEX Ltd"}
    - {path: values.status, op: eq, value: overdue}

- question: "invoices issued in the second quarter of 2026"
  expectedDocType: invoice
  expectedFilters:
    - {path: values.issueDate, op: range, gte: "2026-04-01", lt: "2026-07-01"}

- question: "invoices over 5000"
  expectedDocType: invoice
  expectedFilters:
    - {path: values.total, op: range, gt: 5000}

- question: "invoices that are open or overdue"
  expectedDocType: invoice
  expectedFilters:
    - {path: values.status, op: in, values: [open, overdue]}

- question: "delivery notes shipped by Speedy Freight"
  expectedDocType: delivery-note
  expectedFilters:
    - {path: values.carrier, op: eq, value: "Speedy Freight"}

- question: "what delivery notes do we have"
  expectedDocType: delivery-note
  expectedFilters: []

- question: "contracts with Initech"
  expectedDocType: contract
  expectedFilters:
    - {path: values.party, op: eq, value: Initech}

- question: "contracts longer than 12 months"
  expectedDocType: contract
  expectedFilters:
    - {path: values.termMonths, op: range, gt: 12}

- question: "anything mentioning late payment"
  expectNoFilter: true

- question: "what is in the corpus"
  expectNoFilter: true

- question: "invoices for ACEM Corp"
  expectedDocType: invoice
  expectWiden: true

- question: "overdue invoices for Umbrella SA in March 2026"
  expectedDocType: invoice
  expectedFilters:
    - {path: values.customer, op: eq, value: "Umbrella SA"}
    - {path: values.status, op: eq, value: overdue}
    - {path: values.issueDate, op: range, gte: "2026-03-01", lt: "2026-04-01"}

- question: "only high confidence invoice data"
  expectedDocType: invoice
  expectedFilters:
    - {path: conf.min, op: range, gte: 0.7}

- question: "shipments carried by NordCargo weighing more than 20 kg"
  expectedDocType: delivery-note
  expectedFilters:
    - {path: values.carrier, op: eq, value: NordCargo}
    - {path: values.packages.weightKg, op: range, gt: 20}
```

Loader, mirroring `GoldenSet`:

```java
package com.example.springbootrag.eval;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class RecordGoldenSet {

    private static final String RESOURCE = "/eval/records-golden.yaml";

    private RecordGoldenSet() {}

    @SuppressWarnings("unchecked")
    public static List<RecordGoldenEntry> load() {
        try (InputStream in = RecordGoldenSet.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException(RESOURCE + " not found on the classpath");
            List<Map<String, Object>> raw = new Yaml().load(in);
            List<RecordGoldenEntry> out = new ArrayList<>();
            for (Map<String, Object> m : raw) {
                out.add(new RecordGoldenEntry(
                        (String) m.get("question"),
                        (String) m.get("expectedDocType"),
                        (List<Map<String, Object>>) m.getOrDefault("expectedFilters", List.of()),
                        Boolean.TRUE.equals(m.get("expectNoFilter")),
                        Boolean.TRUE.equals(m.get("expectWiden"))));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("could not load " + RESOURCE, e);
        }
    }
}
```

```java
package com.example.springbootrag.eval;

import java.util.List;
import java.util.Map;

/**
 * One golden question. {@code expectNoFilter} and {@code expectWiden} are the two entries that keep
 * the design honest: a metric rewarding only successful extraction trains toward over-extraction,
 * which is the failure that hides answers.
 */
public record RecordGoldenEntry(String question, String expectedDocType,
                                List<Map<String, Object>> expectedFilters,
                                boolean expectNoFilter, boolean expectWiden) {}
```

- [ ] **Step 5: Run the tests**

Run: `./mvnw test "-Dtest=RecordCorpusTest"`
Expected: PASS, 4 tests.

---

## Task 8: The eval

**Files:**
- Create: `src/test/java/com/example/springbootrag/eval/RecordFilterEvalTest.java`
- Modify: `pom.xml:21` - add `eval-records` to `excludedGroups`

**Interfaces:**
- Consumes: `RecordCorpus.generate`, `RecordGoldenSet.load`, `QueryUnderstanding.extract`, `SearchService.search`, `BackendMetrics.of`.
- Produces: a printed report; no assertions on quality yet.

- [ ] **Step 1: Add the tag to the excluded groups**

`pom.xml`:

```xml
<excludedGroups>eval,eval-judge,eval-wiki,eval-feedback,eval-records</excludedGroups>
```

- [ ] **Step 2: Write the eval**

```java
package com.example.springbootrag.eval;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Reports what query understanding is worth on a corpus that exists on every machine.
 *
 * <p>Unlike {@code WikiRetrievalEvalTest} this uses Testcontainers and a committed synthetic
 * corpus, so it runs on a fresh clone and in CI. It reports, it does not gate - the same order
 * drill C followed before turning the wiki eval into a regression gate.
 *
 * <p>Run: ./mvnw test "-Dgroups=eval-records" "-DexcludedGroups="
 */
@Tag("eval-records")
class RecordFilterEvalTest {
    // container setup identical to RecordIngestIntegrationTest, plus the real ChatProvider
```

The body:

1. `@BeforeAll`-style seeding: create one project, ingest `RecordCorpus.generate(42)` through
   `RecordIngestService` with the fake embedding provider (a real one would take hours; retrieval
   quality here is measured against the FILTER, not against embedding quality - state that in the
   printed header so nobody misreads the recall numbers).
2. Skip the whole class when no chat model answers, following `FaithfulnessEvalTest`'s skip
   pattern (`Assumptions.assumeTrue`), because extraction needs a live model.
3. For each golden entry: run `QueryUnderstanding.extract`, then `SearchService.search` twice -
   once with the extracted filter, once with `MetadataFilter.none()`.
4. Compute and print:

```
=== query understanding, 15 questions, corpus 210 records ===
condition precision   0.87   (matched conditions / extracted conditions)
condition recall      0.79   (matched conditions / expected conditions)
docType accuracy      0.93
no-filter questions   2/2 correctly left unfiltered
widen rate            2/15
extraction p50        842 ms   (model: qwen3:4b)

recall@5   with extraction 0.87   without 0.60
MRR        with extraction 0.79   without 0.51
```

A condition counts as matched when path and op are equal and the value comparison is
case-insensitive for text. Reuse `BackendMetrics.of(ranks, questionCount)` for recall@5/MRR so the
numbers are computed by the same code as every other eval in the project.

- [ ] **Step 3: Run it**

Run: `./mvnw test "-Dgroups=eval-records" "-DexcludedGroups="`
Expected: the report prints; the test passes (or skips cleanly when Ollama is absent).

- [ ] **Step 4: Confirm the normal build still excludes it**

Run: `./mvnw test`
Expected: green, and `RecordFilterEvalTest` does not appear in the output.

---

## Task 9: Documentation

**Files:**
- Modify: `docs/implementation-notes.md`, `docs/LEARNINGS.md`, `docs/ARCHITECTURE.md`, `docs/RAG-MASTERY.md`, `docs/ROADMAP.md`, `README.md`

- [ ] **Step 1: implementation-notes.md**

Record: the facet SQL (recursive CTE, why `prov` is excluded, why the sample limit is interpolated
rather than bound), the decision that an explicit caller filter wins outright instead of merging,
the widen-on-empty rule, and anything that deviated from this plan.

- [ ] **Step 2: LEARNINGS.md section 20**

New section: query understanding. Cover over-extraction as the real failure mode, why widening
beats a confident refusal, why the model's output is validated against a catalogue derived from
data rather than trusted, and the measured before/after table from Task 8. If extraction turns out
to *hurt* recall on some question class, write that down - a negative result measured is worth more
than a feature assumed.

- [ ] **Step 3: ARCHITECTURE.md**

Add the extraction step to the `/ask` and `/chat/stream` flow diagrams, and add the facets endpoint
to the file map. Add the new failure rows: extraction failure -> unfiltered answer; empty catalogue
-> unfiltered answer.

- [ ] **Step 4: RAG-MASTERY.md**

Re-score section 9 row 4 honestly. It moves to 2 only if the eval shows extraction helps; if the
numbers are flat, say so and leave it at 1 with the evidence. Also update section 11 with what the
next move is.

- [ ] **Step 5: ROADMAP.md**

Mark the frozen-corpus item done: `RecordFilterEvalTest` runs on a fresh clone and in CI.

- [ ] **Step 6: README.md**

Document `GET /projects/{id}/facets`, the `app.understand.*` config table, and the new
`appliedFilter` / `widened` response fields plus the `filter` NDJSON frame.

- [ ] **Step 7: Final full suite**

Run: `./mvnw test`
Expected: 280 pre-existing plus roughly 35 new, 0 failures, 3 skipped.

---

## Self-Review Notes

- **Spec coverage:** section 1 -> Task 1; section 2 -> Tasks 2, 3; section 3 -> Tasks 5, 6;
  section 4 -> Tasks 4, 5, 6; section 5 -> Tasks 1-3, 5, 6; section 6 -> the error cases asserted in
  Tasks 2, 3, 5; section 7 -> Tasks 7, 8; section 8 -> every task's tests; section 9 -> Task 9.
- **Ordering note:** Task 4 (trace columns) precedes Tasks 5 and 6 deliberately, so the wiring
  tasks have somewhere to record the filter rather than needing a second pass.
- **Known risk, stated up front:** the eval seeds 210 records with a FAKE embedding provider, so its
  recall numbers measure the filter, not embedding quality. The printed header must say so, or the
  numbers will be quoted out of context later - exactly what happened to the "hybrid beats vector"
  claim before `WikiRetrievalEvalTest` measured it on a second corpus.
- **Accepted gap:** no UI. The `filter` frame and `appliedFilter` field exist for a client that does
  not yet render them.
