# AI Search Over Extracted Records - Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Index extraction-pipeline JSON records (open schema, nested, noisy) and retrieve them with structured metadata filters applied inside all six search backends.

**Architecture:** A record arrives as arbitrary JSON. `ValueWrapper` strips extraction provenance (confidence, page, bbox) so it never reaches embedded text. `RecordRenderer` turns the remaining values into `(text, breadcrumb, metadata)` blocks - generically when no config exists, guided by a per-(project, docType) `render_profile` row when it does. Blocks go through the existing `IngestService` chunk path. Metadata is stored per chunk as JSONB in Postgres and as a nested payload object in Qdrant, and a filter DSL is translated into a predicate **inside** every backend query.

**Tech Stack:** Java 21 target on Java 25 runtime, Spring Boot 3.5.6, raw `JdbcTemplate`, Postgres 16 + pgvector, Qdrant v1.9.0 Java client, Jackson (already a dependency), JUnit 5 + Testcontainers.

**Spec:** `docs/superpowers/specs/2026-08-06-record-search-design.md`

## Global Constraints

- Build and test with `./mvnw`, never `mvn`. On PowerShell, quote `-D` args: `./mvnw test "-Dtest=ValueWrapperTest"`.
- No new dependencies. Jackson, JdbcTemplate, Qdrant client, JUnit 5, Testcontainers are all already present. If a task seems to need a new dependency, stop and ask.
- No Lombok. Plain records and constructors, matching every existing class.
- SQL stays visible in repositories via `JdbcTemplate` - no JPA, no query builder.
- `SearchContext ctx` remains the first argument of every retrieval method. A metadata filter may only NARROW results; it never replaces the access-label predicate.
- Schema changes go in `src/main/resources/schema.sql`, idempotent (`IF NOT EXISTS` / `ADD COLUMN IF NOT EXISTS`), because the file is re-run on every startup.
- **Commits are the user's job.** Each task ends at "full suite green". Do not run `git add` or `git commit` unless the user asks in that same session.
- Keep `docs/implementation-notes.md` updated with any decision that deviates from this plan.

## Deviation from the spec (decided while planning)

The spec writes metadata keys as flat dotted paths (`"customer.name"`, `"_confidence.min"`). **Qdrant parses `.` in a filter key as a nested-path separator**, so a literal payload key containing a dot cannot be matched, and the two stores would disagree. The stored shape is therefore three nested trees instead:

```json
{
  "values": { "customer": { "name": "ACME" }, "issueDate": "2026-05-02" },
  "prov":   { "customer": { "name": { "confidence": 0.82, "page": 2, "bbox": [12,44,90,60] } } },
  "conf":   { "min": 0.71, "avg": 0.88 }
}
```

Filter paths stay dotted in the API (`values.customer.name`, `prov.customer.name.confidence`, `conf.min`) and each translator splits them: Postgres `metadata #>> '{values,customer,name}'`, Qdrant `values.customer.name`. Array markers `[]` are dropped from paths, because an array element is its own chunk and carries its own scalars - `lineItems[].sku` becomes `values.lineItems.sku`. Record this in `docs/implementation-notes.md` during Task 1.

---

## File Structure

**New main sources:**
- `record/ValueWrapper.java` - detects and strips extraction provenance. Pure.
- `record/RenderedBlock.java` - record `(String text, String breadcrumb, Map<String,Object> values, Map<String,Object> prov)`.
- `record/RecordRenderer.java` - JSON + optional profile -> `List<RenderedBlock>`. Pure.
- `record/RenderProfile.java` - profile model + JSON body parsing. Pure.
- `record/RecordHash.java` - canonical-JSON sha256. Pure.
- `repository/ProfileRepository.java` - `render_profile` CRUD.
- `repository/DocumentRegistry.java` - `document` table CRUD.
- `service/RecordIngestService.java` - hash, dirty check, render, delegate to `IngestService`.
- `web/RecordController.java` - `POST /projects/{id}/records`, `DELETE /projects/{id}/records/{docId}`.
- `web/ProfileController.java` - profile endpoints.
- `web/dto/RecordRequest.java`, `web/dto/RecordResponse.java`.
- `repository/MetadataFilter.java` - DSL model + validation. Pure.
- `repository/FilterSql.java` - filter -> SQL fragment + args. Pure.
- `repository/FilterQdrant.java` - filter -> Qdrant conditions.

**Modified:**
- `src/main/resources/schema.sql` - `chunks.doc_type`, `chunks.metadata`, `document`, `render_profile`.
- `repository/PgVectorRepository.java` - insert carries doc_type/metadata; search takes a filter.
- `repository/PgFtsRepository.java` - search takes a filter.
- `repository/QdrantRepository.java` - upsert carries metadata payload; search takes a filter.
- `repository/DocEdgeRepository.java` - `deleteByDstDoc`.
- `service/IngestService.java` - doc_type/metadata pass-through, delete depth + ordering fix.
- `service/SearchService.java` - filter threaded through all six backends.
- `web/SearchController.java`, `web/ChatController.java`, `web/dto/ChatRequest.java` - filter transport.

---

## Task 1: Schema and metadata storage

**Files:**
- Modify: `src/main/resources/schema.sql`
- Modify: `src/main/java/com/example/springbootrag/repository/PgVectorRepository.java:27-39`
- Modify: `src/main/java/com/example/springbootrag/service/IngestService.java:133-156`
- Modify: `src/main/java/com/example/springbootrag/repository/QdrantRepository.java:85-107`
- Test: `src/test/java/com/example/springbootrag/integration/RecordMetadataIntegrationTest.java`
- Modify: `docs/implementation-notes.md`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `PgVectorRepository.insert(long projectId, String docId, int chunkIndex, String content, String sourceFile, String headingPath, float[] embedding, Instant updatedAt, List<String> allowedGroups, String docType, String metadataJson)` - the old 9-arg overload stays and delegates with `(null, "{}")`.
  - `QdrantRepository.upsert(long id, long projectId, String docId, int chunkIndex, String content, String sourceFile, String headingPath, float[] embedding, List<String> allowedGroups, String docType, String metadataJson)` - old overload delegates the same way.
  - `IngestService.ingestChunks(long projectId, String docId, String sourceFile, List<Chunk> chunks, Instant updatedAt, List<String> allowedGroups, String docType, List<String> perChunkMetadataJson)` - `perChunkMetadataJson` is null or the same size as `chunks`.

- [ ] **Step 1: Write the failing integration test**

Copy the container setup from `src/test/java/com/example/springbootrag/integration/DocumentIntegrationTest.java` (same `@SpringBootTest` + `@Testcontainers` pattern, same pgvector and Qdrant images) and add:

```java
@Test
void metadataRoundTripsThroughPostgresAndQdrant() throws Exception {
    long projectId = projectService.defaultProjectId();
    String meta = """
        {"values":{"customer":{"name":"ACME"}},"prov":{},"conf":{"min":0.9,"avg":0.9}}""";

    long id = pgVector.insert(projectId, "REC-1", 0, "Customer: ACME", "REC-1.json",
            "customer", vec(0.1f), null, List.of("public"), "invoice", meta);

    String stored = jdbc.queryForObject(
            "SELECT metadata->'values'->'customer'->>'name' FROM chunks WHERE id = ?",
            String.class, id);
    assertThat(stored).isEqualTo("ACME");

    String docType = jdbc.queryForObject(
            "SELECT doc_type FROM chunks WHERE id = ?", String.class, id);
    assertThat(docType).isEqualTo("invoice");
}

@Test
void metadataColumnDefaultsToEmptyObjectForMarkdownIngest() {
    long projectId = projectService.defaultProjectId();
    ingestService.ingestMarkdown(projectId, "MD-1", "MD-1.md", "# Title\n\nBody text.");

    Integer nonEmpty = jdbc.queryForObject(
            "SELECT count(*) FROM chunks WHERE doc_id = 'MD-1' AND metadata <> '{}'::jsonb",
            Integer.class);
    assertThat(nonEmpty).isZero();
}

private static float[] vec(float fill) {
    float[] v = new float[768];
    java.util.Arrays.fill(v, fill);
    return v;
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw test "-Dtest=RecordMetadataIntegrationTest"`
Expected: compile error - `insert` has no 11-arg overload.

- [ ] **Step 3: Add the schema**

Append to `src/main/resources/schema.sql`:

```sql
-- ---- Extracted-record support (2026-08-06) ----
-- doc_type is the render-profile lookup key and a filter field; it is deliberately free-form,
-- because the set of document types an extraction pipeline produces is open.
-- metadata holds three nested trees: values (extracted data), prov (confidence/page/bbox),
-- conf (per-chunk min/avg). Nested, not dotted-flat, because Qdrant parses '.' in a filter key
-- as a path separator and the two stores must agree on one shape.
ALTER TABLE chunks ADD COLUMN IF NOT EXISTS doc_type VARCHAR(128);
ALTER TABLE chunks ADD COLUMN IF NOT EXISTS metadata JSONB NOT NULL DEFAULT '{}'::jsonb;

CREATE INDEX IF NOT EXISTS idx_chunks_metadata ON chunks USING gin (metadata jsonb_path_ops);
CREATE INDEX IF NOT EXISTS idx_chunks_doc_type ON chunks (project_id, doc_type);
```

- [ ] **Step 4: Add the repository overloads**

In `PgVectorRepository`, keep the existing 9-arg `insert` as a delegating overload and add:

```java
public long insert(long projectId, String docId, int chunkIndex, String content,
                   String sourceFile, String headingPath, float[] embedding,
                   java.time.Instant updatedAt, List<String> allowedGroups) {
    return insert(projectId, docId, chunkIndex, content, sourceFile, headingPath, embedding,
            updatedAt, allowedGroups, null, null);
}

/** {@code metadataJson} null means "{}" - the markdown path stores no record metadata. */
public long insert(long projectId, String docId, int chunkIndex, String content,
                   String sourceFile, String headingPath, float[] embedding,
                   java.time.Instant updatedAt, List<String> allowedGroups,
                   String docType, String metadataJson) {
    String groupsLiteral = toArrayLiteral(allowedGroups);
    return jdbc.queryForObject(
            "INSERT INTO chunks (project_id, doc_id, chunk_index, content, source_file, heading_path, " +
                    "embedding, updated_at, allowed_groups, doc_type, metadata) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?::vector, ?, ?::text[], ?, ?::jsonb) RETURNING id",
            Long.class,
            projectId, docId, chunkIndex, content, sourceFile, headingPath,
            toVectorLiteral(embedding),
            updatedAt == null ? null : java.sql.Timestamp.from(updatedAt),
            groupsLiteral, docType,
            metadataJson == null || metadataJson.isBlank() ? "{}" : metadataJson);
}
```

In `QdrantRepository`, same shape. The metadata payload is stored **nested** so Qdrant path filters work:

```java
public void upsert(long id, long projectId, String docId, int chunkIndex, String content,
                   String sourceFile, String headingPath, float[] embedding,
                   List<String> allowedGroups)
        throws ExecutionException, InterruptedException {
    upsert(id, projectId, docId, chunkIndex, content, sourceFile, headingPath, embedding,
            allowedGroups, null, null);
}

public void upsert(long id, long projectId, String docId, int chunkIndex, String content,
                   String sourceFile, String headingPath, float[] embedding,
                   List<String> allowedGroups, String docType, String metadataJson)
        throws ExecutionException, InterruptedException {
    Map<String, Value> payload = new HashMap<>();
    payload.put("project_id", value(projectId));
    payload.put("doc_id", value(docId));
    payload.put("chunk_index", value((long) chunkIndex));
    payload.put("content", value(content));
    payload.put(ALLOWED_GROUPS, groupsValue(allowedGroups));
    if (sourceFile != null) payload.put("source_file", value(sourceFile));
    if (headingPath != null) payload.put("heading_path", value(headingPath));
    if (docType != null) payload.put("doc_type", value(docType));
    if (metadataJson != null && !metadataJson.isBlank() && !"{}".equals(metadataJson)) {
        payload.putAll(JsonPayload.toQdrant(metadataJson));   // top-level keys: values, prov, conf
    }
    PointStruct point = PointStruct.newBuilder()
            .setId(id(id))
            .setVectors(vectors(embedding))
            .putAllPayload(payload)
            .build();
    client.upsertAsync(collection, List.of(point)).get();
}
```

Add the small converter `src/main/java/com/example/springbootrag/repository/JsonPayload.java`:

```java
package com.example.springbootrag.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qdrant.client.grpc.JsonWithInt.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts a JSON object string into Qdrant payload values, preserving nesting. */
final class JsonPayload {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonPayload() {}

    static Map<String, Value> toQdrant(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            Map<String, Value> out = new LinkedHashMap<>();
            root.fields().forEachRemaining(e -> out.put(e.getKey(), node(e.getValue())));
            return out;
        } catch (Exception e) {
            throw new IllegalArgumentException("metadata is not valid JSON", e);
        }
    }

    private static Value node(JsonNode n) {
        if (n.isObject()) {
            io.qdrant.client.grpc.JsonWithInt.Struct.Builder s =
                    io.qdrant.client.grpc.JsonWithInt.Struct.newBuilder();
            n.fields().forEachRemaining(e -> s.putFields(e.getKey(), node(e.getValue())));
            return Value.newBuilder().setStructValue(s.build()).build();
        }
        if (n.isArray()) {
            List<Value> items = new ArrayList<>();
            n.forEach(item -> items.add(node(item)));
            return io.qdrant.client.ValueFactory.list(items);
        }
        if (n.isNumber()) return io.qdrant.client.ValueFactory.value(n.doubleValue());
        if (n.isBoolean()) return io.qdrant.client.ValueFactory.value(n.booleanValue());
        if (n.isNull()) return Value.newBuilder().setNullValueValue(0).build();
        return io.qdrant.client.ValueFactory.value(n.asText());
    }
}
```

- [ ] **Step 5: Thread it through IngestService**

In `IngestService.ingestChunks`, add the 8-arg overload and keep the 6-arg one delegating with `(null, null)`:

```java
public int ingestChunks(long projectId, String docId, String sourceFile, List<Chunk> chunks,
                        Instant updatedAt, List<String> allowedGroups) {
    return ingestChunks(projectId, docId, sourceFile, chunks, updatedAt, allowedGroups, null, null);
}

/** {@code perChunkMetadataJson}, when non-null, must be the same size as {@code chunks}. */
public int ingestChunks(long projectId, String docId, String sourceFile, List<Chunk> chunks,
                        Instant updatedAt, List<String> allowedGroups,
                        String docType, List<String> perChunkMetadataJson) {
    if (docId == null || docId.isBlank()) {
        throw new IllegalArgumentException("docId is required");
    }
    List<String> groups = resolveGroups(allowedGroups);
    chunks = capToBudget(chunks);
    if (perChunkMetadataJson != null && perChunkMetadataJson.size() != chunks.size()) {
        // capToBudget can split a block; a metadata list that no longer lines up would
        // silently attach one record's provenance to another's text.
        throw new IllegalStateException("metadata list size does not match chunk count");
    }
    delete(projectId, docId);
    for (int i = 0; i < chunks.size(); i++) {
        Chunk chunk = chunks.get(i);
        String meta = perChunkMetadataJson == null ? null : perChunkMetadataJson.get(i);
        float[] vec = embeddings.embed(chunk.text());
        long id = pgVector.insert(projectId, docId, chunk.position(), chunk.text(),
                sourceFile, chunk.headingPath(), vec, updatedAt, groups, docType, meta);
        try {
            qdrant.upsert(id, projectId, docId, chunk.position(), chunk.text(),
                    sourceFile, chunk.headingPath(), vec, groups, docType, meta);
        } catch (ExecutionException | InterruptedException e) {
            throw new IllegalStateException("Qdrant upsert failed", e);
        }
        if (semanticEnabled()) {
            extractAndPersist(projectId, id, chunk.text());
        }
    }
    return chunks.size();
}
```

Note the size guard: `capToBudget` renumbers and can split one block into several, so Task 7 must cap **before** building the metadata list. The guard turns that ordering mistake into a loud failure instead of mismatched provenance.

- [ ] **Step 6: Run the test**

Run: `./mvnw test "-Dtest=RecordMetadataIntegrationTest"`
Expected: PASS, both tests.

- [ ] **Step 7: Run the full suite and record the deviation**

Run: `./mvnw test`
Expected: 188 existing tests still green, plus the 2 new ones.

Add a short section to `docs/implementation-notes.md` titled "Record search - metadata shape" explaining the nested `values`/`prov`/`conf` trees and the Qdrant dot-parsing reason.

---

## Task 2: ValueWrapper - strip extraction provenance

**Files:**
- Create: `src/main/java/com/example/springbootrag/record/ValueWrapper.java`
- Test: `src/test/java/com/example/springbootrag/record/ValueWrapperTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `record ValueWrapper.Unwrapped(JsonNode value, Map<String,Object> provenance, List<String> warnings)`
  - `static Optional<Unwrapped> ValueWrapper.detect(JsonNode node, Keys keys)`
  - `record ValueWrapper.Keys(Set<String> valueKeys, Set<String> confidenceKeys, Set<String> groundingKeys)` with `ValueWrapper.Keys.DEFAULT`.

- [ ] **Step 1: Write the failing test**

```java
package com.example.springbootrag.record;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ValueWrapperTest {

    private static final ObjectMapper M = new ObjectMapper();

    private ValueWrapper.Unwrapped detect(String json) throws Exception {
        Optional<ValueWrapper.Unwrapped> u =
                ValueWrapper.detect(M.readTree(json), ValueWrapper.Keys.DEFAULT);
        return u.orElse(null);
    }

    @Test
    void unwrapsValueAndKeepsProvenanceOutOfTheValue() throws Exception {
        ValueWrapper.Unwrapped u = detect("""
            {"value":"ACME Corp","confidence":0.82,
             "grounding":{"page":2,"bbox":[12,44,90,60]}}""");

        assertThat(u).isNotNull();
        assertThat(u.value().asText()).isEqualTo("ACME Corp");
        assertThat(u.provenance()).containsEntry("confidence", 0.82);
        assertThat(u.provenance()).containsEntry("page", 2);
        assertThat(u.provenance()).containsKey("bbox");
    }

    @Test
    void acceptsAlternateValueKeys() throws Exception {
        assertThat(detect("""{"text":"hello","score":0.5}""").value().asText()).isEqualTo("hello");
        assertThat(detect("""{"content":"hi","confidence":0.5}""").value().asText()).isEqualTo("hi");
    }

    @Test
    void unknownExtraKeyMeansNotAWrapper() throws Exception {
        // Failing open: an unrecognised key may be real extracted data, and dropping it
        // silently is worse than a little noise in the rendered text.
        assertThat(detect("""{"value":"ACME","confidence":0.9,"legalForm":"GmbH"}""")).isNull();
    }

    @Test
    void plainObjectIsNotAWrapper() throws Exception {
        assertThat(detect("""{"name":"ACME","city":"Berlin"}""")).isNull();
    }

    @Test
    void objectWithTwoValueKeysIsNotAWrapper() throws Exception {
        assertThat(detect("""{"value":"a","text":"b","confidence":0.5}""")).isNull();
    }

    @Test
    void wrapperWithNoProvenanceStillUnwraps() throws Exception {
        assertThat(detect("""{"value":"ACME"}""").provenance()).isEmpty();
    }

    @Test
    void nonNumericConfidenceIsKeptRawAndNotCoerced() throws Exception {
        ValueWrapper.Unwrapped u = detect("""{"value":"ACME","confidence":"high"}""");
        assertThat(u.provenance()).containsEntry("confidence_raw", "high");
        assertThat(u.provenance()).doesNotContainKey("confidence");
    }

    @Test
    void customKeysFromProfileAreHonoured() throws Exception {
        ValueWrapper.Keys keys = new ValueWrapper.Keys(
                Set.of("val"), Set.of("certainty"), Set.of("locator"));
        Optional<ValueWrapper.Unwrapped> u = ValueWrapper.detect(
                M.readTree("""{"val":"ACME","certainty":0.7,"locator":{"page":1}}"""), keys);
        assertThat(u).isPresent();
        assertThat(u.get().value().asText()).isEqualTo("ACME");
        assertThat(u.get().provenance()).containsEntry("confidence", 0.7);
    }

    @Test
    void nonObjectIsNotAWrapper() throws Exception {
        assertThat(detect("\\"plain string\\"")).isNull();
        assertThat(detect("42")).isNull();
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw test "-Dtest=ValueWrapperTest"`
Expected: compile error - `ValueWrapper` does not exist.

- [ ] **Step 3: Implement**

```java
package com.example.springbootrag.record;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Extraction pipelines wrap each field with provenance:
 * {@code {"value":"ACME","confidence":0.82,"grounding":{"page":2,"bbox":[...]}}}.
 *
 * <p>Confidence scores and bounding boxes must never reach the embedded text - coordinates and
 * scores carry no meaning, dilute the vector, and digit strings match other digit strings. This
 * splits the wrapper into the value (which gets embedded) and provenance (which becomes
 * filterable metadata and, for page/bbox, a deep-linkable citation).
 */
public final class ValueWrapper {

    /** Key names that identify a wrapper. A profile may supply its own set. */
    public record Keys(Set<String> valueKeys, Set<String> confidenceKeys, Set<String> groundingKeys) {

        public static final Keys DEFAULT = new Keys(
                Set.of("value", "text", "content", "raw"),
                Set.of("confidence", "score"),
                Set.of("grounding", "bbox", "boundingBox", "polygon", "page", "pageNumber",
                        "spans", "offsets", "source"));

        boolean isNoise(String key) {
            return confidenceKeys.contains(key) || groundingKeys.contains(key);
        }
    }

    /** The embeddable value plus the provenance stripped off it. */
    public record Unwrapped(JsonNode value, Map<String, Object> provenance, List<String> warnings) {}

    private ValueWrapper() {}

    /**
     * A node is a wrapper when it is an object with EXACTLY ONE value-ish key and every other key
     * is known provenance. An unrecognised key means "not a wrapper": failing open keeps data that
     * a stricter rule would silently discard.
     */
    public static Optional<Unwrapped> detect(JsonNode node, Keys keys) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            return Optional.empty();
        }
        String valueKey = null;
        List<String> unknown = new ArrayList<>();
        var it = node.fieldNames();
        while (it.hasNext()) {
            String name = it.next();
            if (keys.valueKeys().contains(name)) {
                if (valueKey != null) return Optional.empty();   // two value keys: ambiguous
                valueKey = name;
            } else if (!keys.isNoise(name)) {
                unknown.add(name);
            }
        }
        if (valueKey == null || !unknown.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> prov = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> {
            if (e.getKey().equals(valueKeyOf(node, keys))) return;
            collectProvenance(e.getKey(), e.getValue(), keys, prov);
        });
        return Optional.of(new Unwrapped(node.get(valueKey), prov, List.of()));
    }

    private static String valueKeyOf(JsonNode node, Keys keys) {
        var it = node.fieldNames();
        while (it.hasNext()) {
            String name = it.next();
            if (keys.valueKeys().contains(name)) return name;
        }
        return null;
    }

    /**
     * Flattens one provenance entry into normalised keys: confidence, page, bbox, span.
     * A grounding object contributes its own inner keys, so both
     * {@code {"page":2}} and {@code {"grounding":{"page":2}}} land as "page".
     */
    private static void collectProvenance(String key, JsonNode v, Keys keys, Map<String, Object> out) {
        if (keys.confidenceKeys().contains(key)) {
            if (v.isNumber()) {
                out.put("confidence", v.doubleValue());
            } else if (!v.isNull()) {
                // One tenant reporting "high" must not poison a numeric range filter.
                out.put("confidence_raw", v.asText());
            }
            return;
        }
        if (v.isObject()) {
            v.fields().forEachRemaining(e -> collectProvenance(e.getKey(), e.getValue(), keys, out));
            return;
        }
        switch (key) {
            case "page", "pageNumber" -> { if (v.isNumber()) out.put("page", v.intValue()); }
            case "bbox", "boundingBox", "polygon" -> out.put("bbox", toList(v));
            case "spans", "offsets", "source" -> out.put("span", v.isArray() ? toList(v) : v.asText());
            default -> { /* unknown noise key inside grounding: ignore, it is not embeddable */ }
        }
    }

    private static List<Object> toList(JsonNode n) {
        List<Object> out = new ArrayList<>();
        if (n.isArray()) {
            n.forEach(item -> out.add(item.isNumber() ? item.doubleValue() : item.asText()));
        }
        return out;
    }
}
```

- [ ] **Step 4: Run the test**

Run: `./mvnw test "-Dtest=ValueWrapperTest"`
Expected: PASS, 9 tests.

---

## Task 3: RecordRenderer - generic rendering

**Files:**
- Create: `src/main/java/com/example/springbootrag/record/RenderedBlock.java`
- Create: `src/main/java/com/example/springbootrag/record/RecordRenderer.java`
- Test: `src/test/java/com/example/springbootrag/record/RecordRendererTest.java`

**Interfaces:**
- Consumes: `ValueWrapper.detect`, `ValueWrapper.Keys`.
- Produces:
  - `record RenderedBlock(String text, String breadcrumb, Map<String,Object> values, Map<String,Object> prov)`
  - `List<RenderedBlock> RecordRenderer.render(JsonNode record, RenderProfile profile)` - `profile` may be null, meaning fully generic. (`RenderProfile` arrives in Task 4; until then declare the parameter type as `RenderProfile` and create the class as an empty placeholder ONLY if compilation demands it - preferred order is to implement Task 4's model first if you hit that.)
  - `static String RecordRenderer.label(String pathSegment)` - `issueDate` -> `Issue date`.

- [ ] **Step 1: Write the failing test**

```java
package com.example.springbootrag.record;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecordRendererTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final RecordRenderer renderer = new RecordRenderer();

    private List<RenderedBlock> render(String json) throws Exception {
        return renderer.render(M.readTree(json), null);
    }

    @Test
    void topLevelScalarsBecomeOneHeaderBlock() throws Exception {
        List<RenderedBlock> blocks = render("""
            {"invoiceNumber":"INV-5575","issueDate":"2026-05-02","total":1899.5}""");

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).breadcrumb()).isEqualTo("");
        assertThat(blocks.get(0).text())
                .contains("Invoice number: INV-5575")
                .contains("Issue date: 2026-05-02")
                .contains("Total: 1899.5");
    }

    @Test
    void nestedObjectBecomesItsOwnBlock() throws Exception {
        List<RenderedBlock> blocks = render("""
            {"id":"INV-1","customer":{"name":"ACME","city":"Berlin"}}""");

        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(1).breadcrumb()).isEqualTo("customer");
        assertThat(blocks.get(1).text()).contains("Name: ACME").contains("City: Berlin");
    }

    @Test
    void eachArrayElementIsItsOwnBlockWithABreadcrumb() throws Exception {
        List<RenderedBlock> blocks = render("""
            {"id":"INV-1",
             "lineItems":[{"sku":"A-1","description":"Widget"},
                          {"sku":"B-2","description":"Gadget"}]}""");

        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(1).breadcrumb()).isEqualTo("lineItems[0]");
        assertThat(blocks.get(1).text()).contains("Widget");
        assertThat(blocks.get(2).breadcrumb()).isEqualTo("lineItems[1]");
        assertThat(blocks.get(2).text()).contains("Gadget");
    }

    @Test
    void arrayElementBlockCarriesParentScalarContext() throws Exception {
        List<RenderedBlock> blocks = render("""
            {"invoiceNumber":"INV-1","lineItems":[{"sku":"A-1"}]}""");

        assertThat(blocks.get(1).text()).contains("INV-1");
    }

    @Test
    void arrayOfScalarsStaysOneLineInsideItsOwningBlock() throws Exception {
        List<RenderedBlock> blocks = render("""{"id":"X","tags":["urgent","paid"]}""");

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).text()).contains("Tags: urgent, paid");
    }

    @Test
    void nullAndEmptyValuesAreOmitted() throws Exception {
        List<RenderedBlock> blocks = render("""
            {"id":"X","note":null,"comment":"","ok":"yes"}""");

        assertThat(blocks.get(0).text()).doesNotContain("Note").doesNotContain("Comment");
        assertThat(blocks.get(0).text()).contains("Ok: yes");
    }

    @Test
    void wrappedValuesRenderTheValueAndNeverTheProvenance() throws Exception {
        List<RenderedBlock> blocks = render("""
            {"customer":{"value":"ACME Corp","confidence":0.82,
                         "grounding":{"page":2,"bbox":[12,44,90,60]}}}""");

        String allText = blocks.stream().map(RenderedBlock::text).reduce("", String::concat);
        assertThat(allText).contains("ACME Corp");
        assertThat(allText).doesNotContain("0.82").doesNotContain("12").doesNotContain("bbox");
    }

    @Test
    void provenanceLandsInTheProvMapUnderTheFieldPath() throws Exception {
        List<RenderedBlock> blocks = render("""
            {"customer":{"value":"ACME","confidence":0.82,"grounding":{"page":2}}}""");

        assertThat(blocks.get(0).prov()).containsKey("customer");
        assertThat(blocks.get(0).values()).containsEntry("customer", "ACME");
    }

    @Test
    void nestedWrapperInsideAnArrayElementUnwraps() throws Exception {
        List<RenderedBlock> blocks = render("""
            {"lineItems":[{"sku":{"value":"A-1","confidence":0.4}}]}""");

        assertThat(blocks.get(0).text()).contains("A-1").doesNotContain("0.4");
        assertThat(blocks.get(0).prov()).containsKey("sku");
    }

    @Test
    void labelSplitsCamelCaseAndSnakeCase() {
        assertThat(RecordRenderer.label("issueDate")).isEqualTo("Issue date");
        assertThat(RecordRenderer.label("invoice_number")).isEqualTo("Invoice number");
        assertThat(RecordRenderer.label("total")).isEqualTo("Total");
    }

    @Test
    void emptyRecordRendersNoBlocks() throws Exception {
        assertThat(render("{}")).isEmpty();
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw test "-Dtest=RecordRendererTest"`
Expected: compile error - `RecordRenderer` does not exist.

- [ ] **Step 3: Implement RenderedBlock**

```java
package com.example.springbootrag.record;

import java.util.Map;

/**
 * One embeddable block of a record: the text that gets a vector, the JSON path it came from
 * (stored in chunks.heading_path so citations and the chunk viewer work unchanged), the values
 * behind that text, and the provenance stripped off those values.
 */
public record RenderedBlock(String text, String breadcrumb,
                            Map<String, Object> values, Map<String, Object> prov) {}
```

- [ ] **Step 4: Implement RecordRenderer (generic path only)**

```java
package com.example.springbootrag.record;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns an extracted JSON record into embeddable blocks. Works with zero configuration, because
 * the set of document types an extraction pipeline emits is open - an unseen type must be
 * searchable the moment it lands, not after someone writes a schema for it.
 */
public class RecordRenderer {

    /** {@code profile} may be null: fully generic rendering. */
    public List<RenderedBlock> render(JsonNode record, RenderProfile profile) {
        List<RenderedBlock> out = new ArrayList<>();
        if (record == null || !record.isObject()) {
            return out;
        }
        ValueWrapper.Keys keys = profile == null ? ValueWrapper.Keys.DEFAULT : profile.wrapperKeys();

        Block header = new Block("");
        List<Runnable> deferred = new ArrayList<>();

        record.fields().forEachRemaining(entry -> {
            String name = entry.getKey();
            JsonNode child = entry.getValue();
            if (profile != null && profile.isExcluded(name)) return;

            Optional<ValueWrapper.Unwrapped> wrapped = ValueWrapper.detect(child, keys);
            if (wrapped.isPresent()) {
                addScalar(header, name, wrapped.get().value(), wrapped.get().provenance(), profile);
                return;
            }
            if (child.isArray() && isArrayOfObjects(child)) {
                deferred.add(() -> {
                    for (int i = 0; i < child.size(); i++) {
                        Block element = new Block(name + "[" + i + "]");
                        // Parent scalars first: an element chunk alone ("SKU: A-1") is
                        // unanswerable without knowing which invoice it belongs to.
                        element.lines.addAll(header.lines);
                        fillFrom(element, child.get(i), keys, profile, name);
                        emit(out, element);
                    }
                });
                return;
            }
            if (child.isObject()) {
                deferred.add(() -> {
                    Block section = new Block(name);
                    fillFrom(section, child, keys, profile, name);
                    emit(out, section);
                });
                return;
            }
            addScalar(header, name, child, Map.of(), profile);
        });

        emit(out, header);
        deferred.forEach(Runnable::run);
        return out;
    }

    /* ---- internals ---- */

    private static final class Block {
        final String breadcrumb;
        final List<String> lines = new ArrayList<>();
        final Map<String, Object> values = new LinkedHashMap<>();
        final Map<String, Object> prov = new LinkedHashMap<>();
        Block(String breadcrumb) { this.breadcrumb = breadcrumb; }
    }

    private void fillFrom(Block block, JsonNode obj, ValueWrapper.Keys keys,
                          RenderProfile profile, String prefix) {
        obj.fields().forEachRemaining(e -> {
            String name = e.getKey();
            String path = prefix == null || prefix.isEmpty() ? name : prefix + "." + name;
            if (profile != null && profile.isExcluded(path)) return;
            JsonNode v = e.getValue();
            Optional<ValueWrapper.Unwrapped> wrapped = ValueWrapper.detect(v, keys);
            if (wrapped.isPresent()) {
                addScalar(block, name, wrapped.get().value(), wrapped.get().provenance(), profile);
            } else if (v.isObject()) {
                fillFrom(block, v, keys, profile, name);
            } else {
                addScalar(block, name, v, Map.of(), profile);
            }
        });
    }

    private void addScalar(Block block, String name, JsonNode v,
                           Map<String, Object> provenance, RenderProfile profile) {
        if (v == null || v.isNull()) return;
        String rendered = v.isArray() ? joinScalars(v) : v.asText();
        if (rendered.isBlank()) return;

        if (!provenance.isEmpty()) {
            block.prov.put(name, provenance);
        }
        block.values.put(name, v.isNumber() ? v.doubleValue() : rendered);
        if (profile != null && profile.isFilterOnly(name)) {
            return;   // metadata yes, embedded text no
        }
        String label = profile == null ? label(name) : profile.labelFor(name);
        block.lines.add(label + ": " + rendered);
    }

    private static String joinScalars(JsonNode array) {
        List<String> parts = new ArrayList<>();
        array.forEach(item -> { if (!item.isObject() && !item.isNull()) parts.add(item.asText()); });
        return String.join(", ", parts);
    }

    private static boolean isArrayOfObjects(JsonNode array) {
        for (JsonNode item : array) {
            if (item.isObject()) return true;
        }
        return false;
    }

    private static void emit(List<RenderedBlock> out, Block b) {
        if (b.lines.isEmpty()) return;
        out.add(new RenderedBlock(String.join("\\n", b.lines), b.breadcrumb, b.values, b.prov));
    }

    /** {@code issueDate} -> "Issue date". Readable labels embed better than raw keys. */
    public static String label(String segment) {
        String spaced = segment.replace('_', ' ')
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .trim()
                .toLowerCase();
        return spaced.isEmpty() ? segment
                : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
```

- [ ] **Step 5: Run the test**

Run: `./mvnw test "-Dtest=RecordRendererTest"`
Expected: PASS, 11 tests. (Requires `RenderProfile` from Task 4 to compile - implement Task 4's model class first if the compiler complains; the two tasks share one compilation unit boundary.)

---

## Task 4: RenderProfile - optional per-type configuration

**Files:**
- Create: `src/main/java/com/example/springbootrag/record/RenderProfile.java`
- Test: `src/test/java/com/example/springbootrag/record/RenderProfileTest.java`
- Test: `src/test/java/com/example/springbootrag/record/RecordRendererProfileTest.java`

**Interfaces:**
- Consumes: `ValueWrapper.Keys`, `RecordRenderer`.
- Produces:
  - `static RenderProfile RenderProfile.parse(String json)` - throws `IllegalArgumentException` on malformed JSON.
  - `boolean isExcluded(String path)`, `boolean isFilterOnly(String path)`, `String labelFor(String path)`, `ValueWrapper.Keys wrapperKeys()`, `boolean isBoundary(String path)`.

- [ ] **Step 1: Write the failing tests**

```java
package com.example.springbootrag.record;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenderProfileTest {

    @Test
    void excludeWinsOverInclude() {
        RenderProfile p = RenderProfile.parse("""
            {"include":["a","b"],"exclude":["b"]}""");

        assertThat(p.isExcluded("b")).isTrue();
        assertThat(p.isExcluded("a")).isFalse();
    }

    @Test
    void emptyIncludeMeansEverythingNotExcluded() {
        RenderProfile p = RenderProfile.parse("""{"exclude":["secret"]}""");

        assertThat(p.isExcluded("anything")).isFalse();
        assertThat(p.isExcluded("secret")).isTrue();
    }

    @Test
    void nonEmptyIncludeExcludesEverythingElse() {
        RenderProfile p = RenderProfile.parse("""{"include":["customer.name"]}""");

        assertThat(p.isExcluded("customer.name")).isFalse();
        assertThat(p.isExcluded("internalNotes")).isTrue();
    }

    @Test
    void wildcardExcludeMatchesAPrefix() {
        RenderProfile p = RenderProfile.parse("""{"exclude":["internal.*"]}""");

        assertThat(p.isExcluded("internal.batchId")).isTrue();
        assertThat(p.isExcluded("internalish")).isFalse();
    }

    @Test
    void labelsOverrideTheDerivedLabel() {
        RenderProfile p = RenderProfile.parse("""{"labels":{"issueDate":"Invoice date"}}""");

        assertThat(p.labelFor("issueDate")).isEqualTo("Invoice date");
        assertThat(p.labelFor("total")).isEqualTo("Total");
    }

    @Test
    void filterOnlyPathsAreMarked() {
        RenderProfile p = RenderProfile.parse("""{"filterOnly":["internal.batchId"]}""");

        assertThat(p.isFilterOnly("internal.batchId")).isTrue();
        assertThat(p.isFilterOnly("customer")).isFalse();
    }

    @Test
    void wrapperKeysComeFromTheProfileWhenDeclared() {
        RenderProfile p = RenderProfile.parse("""
            {"wrapper":{"valueKeys":["val"],"confidenceKeys":["certainty"],
                        "groundingKeys":["locator"]}}""");

        assertThat(p.wrapperKeys().valueKeys()).containsExactly("val");
        assertThat(p.wrapperKeys().confidenceKeys()).containsExactly("certainty");
    }

    @Test
    void wrapperKeysFallBackToDefaults() {
        assertThat(RenderProfile.parse("{}").wrapperKeys()).isEqualTo(ValueWrapper.Keys.DEFAULT);
    }

    @Test
    void malformedJsonIsRejected() {
        assertThatThrownBy(() -> RenderProfile.parse("not json"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

And the renderer-with-profile test:

```java
package com.example.springbootrag.record;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecordRendererProfileTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final RecordRenderer renderer = new RecordRenderer();

    @Test
    void excludedPathNeverReachesTextOrValues() throws Exception {
        RenderProfile p = RenderProfile.parse("""{"exclude":["rawOcrText"]}""");
        List<RenderedBlock> blocks = renderer.render(
                M.readTree("""{"id":"X","rawOcrText":"noisy dump"}"""), p);

        assertThat(blocks.get(0).text()).doesNotContain("noisy dump");
        assertThat(blocks.get(0).values()).doesNotContainKey("rawOcrText");
    }

    @Test
    void filterOnlyPathIsInValuesButNotInText() throws Exception {
        RenderProfile p = RenderProfile.parse("""{"filterOnly":["batchId"]}""");
        List<RenderedBlock> blocks = renderer.render(
                M.readTree("""{"id":"X","batchId":"B-77"}"""), p);

        assertThat(blocks.get(0).text()).doesNotContain("B-77");
        assertThat(blocks.get(0).values()).containsEntry("batchId", "B-77");
    }

    @Test
    void profileLabelIsUsedInTheRenderedLine() throws Exception {
        RenderProfile p = RenderProfile.parse("""{"labels":{"issueDate":"Invoice date"}}""");
        List<RenderedBlock> blocks = renderer.render(
                M.readTree("""{"issueDate":"2026-05-02"}"""), p);

        assertThat(blocks.get(0).text()).contains("Invoice date: 2026-05-02");
    }

    @Test
    void profileWrapperKeysUnwrapATenantSpecificShape() throws Exception {
        RenderProfile p = RenderProfile.parse("""
            {"wrapper":{"valueKeys":["val"],"confidenceKeys":["certainty"],"groundingKeys":[]}}""");
        List<RenderedBlock> blocks = renderer.render(
                M.readTree("""{"customer":{"val":"ACME","certainty":0.9}}"""), p);

        assertThat(blocks.get(0).text()).contains("ACME").doesNotContain("0.9");
    }
}
```

- [ ] **Step 2: Run and watch fail**

Run: `./mvnw test "-Dtest=RenderProfileTest+RecordRendererProfileTest"`
Expected: compile error - `RenderProfile` does not exist.

- [ ] **Step 3: Implement**

```java
package com.example.springbootrag.record;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Optional per-(project, docType) rendering configuration. Data, not code: a tenant with a known
 * schema inserts a row and gets better labels and boundaries; a tenant with an unknown schema
 * inserts nothing and still gets a fully searchable document.
 */
public final class RenderProfile {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Set<String> include;
    private final Set<String> exclude;
    private final Map<String, String> labels;
    private final Set<String> filterOnly;
    private final Set<String> boundaries;
    private final ValueWrapper.Keys wrapperKeys;

    private RenderProfile(Set<String> include, Set<String> exclude, Map<String, String> labels,
                          Set<String> filterOnly, Set<String> boundaries,
                          ValueWrapper.Keys wrapperKeys) {
        this.include = include;
        this.exclude = exclude;
        this.labels = labels;
        this.filterOnly = filterOnly;
        this.boundaries = boundaries;
        this.wrapperKeys = wrapperKeys;
    }

    public static RenderProfile parse(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isObject()) throw new IllegalArgumentException("profile must be a JSON object");
            return new RenderProfile(
                    stringSet(root.get("include")),
                    stringSet(root.get("exclude")),
                    stringMap(root.get("labels")),
                    stringSet(root.get("filterOnly")),
                    stringSet(root.get("boundaries")),
                    wrapper(root.get("wrapper")));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("profile is not valid JSON", e);
        }
    }

    /** Exclude wins over include; an empty include means "everything not excluded". */
    public boolean isExcluded(String path) {
        if (matches(exclude, path)) return true;
        return !include.isEmpty() && !matches(include, path);
    }

    public boolean isFilterOnly(String path) {
        return matches(filterOnly, path);
    }

    public boolean isBoundary(String path) {
        return matches(boundaries, path);
    }

    public String labelFor(String path) {
        String custom = labels.get(path);
        return custom != null ? custom : RecordRenderer.label(path);
    }

    public ValueWrapper.Keys wrapperKeys() {
        return wrapperKeys;
    }

    /** Supports an exact match and a trailing "prefix.*" wildcard. */
    private static boolean matches(Set<String> patterns, String path) {
        if (patterns.contains(path)) return true;
        for (String p : patterns) {
            if (p.endsWith(".*") && path.startsWith(p.substring(0, p.length() - 1))) return true;
            if (p.endsWith("[]") && path.startsWith(p.substring(0, p.length() - 2))) return true;
        }
        return false;
    }

    private static Set<String> stringSet(JsonNode n) {
        Set<String> out = new LinkedHashSet<>();
        if (n != null && n.isArray()) n.forEach(v -> out.add(v.asText()));
        return out;
    }

    private static Map<String, String> stringMap(JsonNode n) {
        Map<String, String> out = new LinkedHashMap<>();
        if (n != null && n.isObject()) n.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        return out;
    }

    private static ValueWrapper.Keys wrapper(JsonNode n) {
        if (n == null || !n.isObject()) return ValueWrapper.Keys.DEFAULT;
        Set<String> values = stringSet(n.get("valueKeys"));
        Set<String> conf = stringSet(n.get("confidenceKeys"));
        Set<String> ground = stringSet(n.get("groundingKeys"));
        if (values.isEmpty()) return ValueWrapper.Keys.DEFAULT;
        return new ValueWrapper.Keys(values,
                conf.isEmpty() ? Set.of("confidence", "score") : conf,
                ground);
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./mvnw test "-Dtest=RenderProfileTest+RecordRendererProfileTest+RecordRendererTest"`
Expected: PASS, all three classes.

---

## Task 5: Profile storage and endpoints

**Files:**
- Modify: `src/main/resources/schema.sql`
- Create: `src/main/java/com/example/springbootrag/repository/ProfileRepository.java`
- Create: `src/main/java/com/example/springbootrag/web/ProfileController.java`
- Test: `src/test/java/com/example/springbootrag/integration/ProfileRepositoryIntegrationTest.java`

**Interfaces:**
- Consumes: `RenderProfile.parse`.
- Produces:
  - `record ProfileRepository.StoredProfile(String docType, String body, int version)`
  - `int upsert(long projectId, String docType, String body)` - returns the new version.
  - `Optional<StoredProfile> find(long projectId, String docType)`
  - `List<StoredProfile> list(long projectId)`

- [ ] **Step 1: Write the failing integration test**

```java
@Test
void upsertBumpsVersionAndKeepsBody() {
    long projectId = projectService.defaultProjectId();

    int v1 = profiles.upsert(projectId, "invoice", """{"exclude":["rawOcrText"]}""");
    assertThat(v1).isEqualTo(1);

    int v2 = profiles.upsert(projectId, "invoice", """{"exclude":["rawOcrText","internal.*"]}""");
    assertThat(v2).isEqualTo(2);

    var found = profiles.find(projectId, "invoice").orElseThrow();
    assertThat(found.version()).isEqualTo(2);
    assertThat(found.body()).contains("internal.*");
}

@Test
void missingProfileIsEmptyNotAnError() {
    assertThat(profiles.find(projectService.defaultProjectId(), "never-seen")).isEmpty();
}

@Test
void listReturnsOneRowPerDocType() {
    long projectId = projectService.defaultProjectId();
    profiles.upsert(projectId, "invoice", "{}");
    profiles.upsert(projectId, "contract", "{}");

    assertThat(profiles.list(projectId)).extracting("docType")
            .contains("invoice", "contract");
}
```

- [ ] **Step 2: Run and watch fail**

Run: `./mvnw test "-Dtest=ProfileRepositoryIntegrationTest"`
Expected: compile error - `ProfileRepository` does not exist.

- [ ] **Step 3: Add the schema**

```sql
-- One optional rendering configuration per (project, docType). Absent = generic rendering.
-- version is bumped on every write and participates in the freshness hash, so editing a profile
-- re-indexes exactly the documents of that type and nothing else.
CREATE TABLE IF NOT EXISTS render_profile (
    project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    doc_type   VARCHAR(128) NOT NULL,
    body       JSONB NOT NULL,
    version    INT NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, doc_type)
);
```

- [ ] **Step 4: Implement the repository**

```java
package com.example.springbootrag.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ProfileRepository {

    public record StoredProfile(String docType, String body, int version) {}

    private final JdbcTemplate jdbc;

    public ProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Upsert; returns the version after the write. */
    public int upsert(long projectId, String docType, String body) {
        Integer version = jdbc.queryForObject(
                "INSERT INTO render_profile (project_id, doc_type, body) VALUES (?, ?, ?::jsonb) " +
                        "ON CONFLICT (project_id, doc_type) DO UPDATE " +
                        "SET body = EXCLUDED.body, version = render_profile.version + 1, updated_at = now() " +
                        "RETURNING version",
                Integer.class, projectId, docType, body);
        return version == null ? 1 : version;
    }

    public Optional<StoredProfile> find(long projectId, String docType) {
        List<StoredProfile> rows = jdbc.query(
                "SELECT doc_type, body::text AS body, version FROM render_profile " +
                        "WHERE project_id = ? AND doc_type = ?",
                (rs, n) -> new StoredProfile(rs.getString("doc_type"), rs.getString("body"), rs.getInt("version")),
                projectId, docType);
        return rows.stream().findFirst();
    }

    public List<StoredProfile> list(long projectId) {
        return jdbc.query(
                "SELECT doc_type, body::text AS body, version FROM render_profile " +
                        "WHERE project_id = ? ORDER BY doc_type",
                (rs, n) -> new StoredProfile(rs.getString("doc_type"), rs.getString("body"), rs.getInt("version")),
                projectId);
    }
}
```

- [ ] **Step 5: Implement the controller**

```java
package com.example.springbootrag.web;

import com.example.springbootrag.record.RenderProfile;
import com.example.springbootrag.repository.ProfileRepository;
import com.example.springbootrag.repository.ProfileRepository.StoredProfile;
import com.example.springbootrag.service.ProjectService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class ProfileController {

    private final ProfileRepository profiles;
    private final ProjectService projectService;

    public ProfileController(ProfileRepository profiles, ProjectService projectService) {
        this.profiles = profiles;
        this.projectService = projectService;
    }

    /** Body is the raw profile JSON. Parsed before storing so a broken profile fails here. */
    @PutMapping(value = "/projects/{projectId}/profiles/{docType}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> upsert(@PathVariable long projectId, @PathVariable String docType,
                                      @RequestBody String body) {
        requireProject(projectId);
        RenderProfile.parse(body);   // validation only; storage keeps the raw text
        int version = profiles.upsert(projectId, docType, body);
        return Map.of("docType", docType, "version", version);
    }

    @GetMapping("/projects/{projectId}/profiles/{docType}")
    public StoredProfile get(@PathVariable long projectId, @PathVariable String docType) {
        requireProject(projectId);
        return profiles.find(projectId, docType)
                .orElseThrow(() -> new IllegalArgumentException("no profile for docType: " + docType));
    }

    @GetMapping("/projects/{projectId}/profiles")
    public List<StoredProfile> list(@PathVariable long projectId) {
        requireProject(projectId);
        return profiles.list(projectId);
    }

    private void requireProject(long projectId) {
        if (!projectService.exists(projectId)) {
            throw new IllegalArgumentException("project not found: " + projectId);
        }
    }
}
```

- [ ] **Step 6: Run the tests**

Run: `./mvnw test "-Dtest=ProfileRepositoryIntegrationTest"`
Expected: PASS, 3 tests.

---

## Task 6: Document registry and record hashing

**Files:**
- Modify: `src/main/resources/schema.sql`
- Create: `src/main/java/com/example/springbootrag/record/RecordHash.java`
- Create: `src/main/java/com/example/springbootrag/repository/DocumentRegistry.java`
- Test: `src/test/java/com/example/springbootrag/record/RecordHashTest.java`
- Test: `src/test/java/com/example/springbootrag/integration/DocumentRegistryIntegrationTest.java`

**Interfaces:**
- Consumes: `RenderedBlock`.
- Produces:
  - `static String RecordHash.ofJson(JsonNode node)` - canonical (key-sorted) sha256 hex.
  - `static String RecordHash.ofBlocks(List<RenderedBlock> blocks)` - sha256 hex over text + breadcrumb only.
  - `record DocumentRegistry.Entry(String docId, String docType, String origin, String contentHash, String rawHash, String embedModel, Integer profileVersion, List<String> allowedGroups, int chunkCount)`
  - `Optional<Entry> find(long projectId, String docId)`, `void upsert(long projectId, Entry e)`, `void delete(long projectId, String docId)`.

- [ ] **Step 1: Write the failing hash test**

```java
package com.example.springbootrag.record;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecordHashTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void keyOrderDoesNotChangeTheHash() throws Exception {
        String a = RecordHash.ofJson(M.readTree("""{"a":1,"b":2}"""));
        String b = RecordHash.ofJson(M.readTree("""{"b":2,"a":1}"""));

        assertThat(a).isEqualTo(b);
    }

    @Test
    void differentValuesChangeTheHash() throws Exception {
        assertThat(RecordHash.ofJson(M.readTree("""{"a":1}""")))
                .isNotEqualTo(RecordHash.ofJson(M.readTree("""{"a":2}""")));
    }

    @Test
    void arrayOrderDoesChangeTheHash() throws Exception {
        // Arrays are ordered data - line item 1 and line item 2 are not interchangeable.
        assertThat(RecordHash.ofJson(M.readTree("""{"a":[1,2]}""")))
                .isNotEqualTo(RecordHash.ofJson(M.readTree("""{"a":[2,1]}""")));
    }

    @Test
    void blockHashIgnoresProvenanceChanges() {
        List<RenderedBlock> before = List.of(
                new RenderedBlock("Customer: ACME", "", Map.of("customer", "ACME"),
                        Map.of("customer", Map.of("confidence", 0.82))));
        List<RenderedBlock> after = List.of(
                new RenderedBlock("Customer: ACME", "", Map.of("customer", "ACME"),
                        Map.of("customer", Map.of("confidence", 0.83))));

        // The whole point: a re-extraction that only jitters a confidence must not re-embed a
        // corpus to produce byte-identical vectors.
        assertThat(RecordHash.ofBlocks(before)).isEqualTo(RecordHash.ofBlocks(after));
    }

    @Test
    void blockHashChangesWhenTextChanges() {
        List<RenderedBlock> before = List.of(new RenderedBlock("Customer: ACME", "", Map.of(), Map.of()));
        List<RenderedBlock> after = List.of(new RenderedBlock("Customer: OTHER", "", Map.of(), Map.of()));

        assertThat(RecordHash.ofBlocks(before)).isNotEqualTo(RecordHash.ofBlocks(after));
    }
}
```

- [ ] **Step 2: Run and watch fail**

Run: `./mvnw test "-Dtest=RecordHashTest"`
Expected: compile error - `RecordHash` does not exist.

- [ ] **Step 3: Implement RecordHash**

```java
package com.example.springbootrag.record;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Two hashes, on purpose.
 *
 * <p>{@link #ofBlocks} covers the rendered text - what actually gets embedded - and drives
 * re-embedding. {@link #ofJson} covers the raw record and drives a metadata-only refresh. A
 * re-extraction that shifts a confidence from 0.82 to 0.83 changes the second and not the first,
 * so the corpus is not re-embedded to produce byte-identical vectors.
 */
public final class RecordHash {

    private RecordHash() {}

    public static String ofJson(JsonNode node) {
        return sha256(canonical(node));
    }

    public static String ofBlocks(List<RenderedBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (RenderedBlock b : blocks) {
            sb.append(b.breadcrumb()).append('\\u0000').append(b.text()).append('\\u0001');
        }
        return sha256(sb.toString());
    }

    /** Object keys sorted; array order preserved, because array order is data. */
    static String canonical(JsonNode node) {
        if (node == null || node.isNull()) return "null";
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(names.get(i)).append("\\":").append(canonical(node.get(names.get(i))));
            }
            return sb.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(canonical(node.get(i)));
            }
            return sb.append(']').toString();
        }
        return node.isTextual() ? '"' + node.asText() + '"' : node.asText();
    }

    private static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
```

- [ ] **Step 4: Add the registry schema**

```sql
-- One row per indexed document: what was indexed, from what, and under which settings.
-- content_hash covers the RENDERED text (drives re-embedding); raw_hash covers the raw record
-- (drives a cheap metadata refresh when only provenance changed).
CREATE TABLE IF NOT EXISTS document (
    project_id      BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    doc_id          VARCHAR(255) NOT NULL,
    doc_type        VARCHAR(128),
    origin          VARCHAR(32) NOT NULL DEFAULT 'record',
    content_hash    CHAR(64) NOT NULL,
    raw_hash        CHAR(64) NOT NULL,
    embed_model     VARCHAR(128) NOT NULL,
    profile_version INT,
    allowed_groups  TEXT[] NOT NULL,
    chunk_count     INT NOT NULL,
    indexed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, doc_id)
);
```

- [ ] **Step 5: Implement DocumentRegistry**

```java
package com.example.springbootrag.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Repository
public class DocumentRegistry {

    public record Entry(String docId, String docType, String origin, String contentHash,
                        String rawHash, String embedModel, Integer profileVersion,
                        List<String> allowedGroups, int chunkCount) {}

    private final JdbcTemplate jdbc;

    public DocumentRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Entry> find(long projectId, String docId) {
        List<Entry> rows = jdbc.query(
                "SELECT doc_id, doc_type, origin, content_hash, raw_hash, embed_model, " +
                        "profile_version, allowed_groups, chunk_count " +
                        "FROM document WHERE project_id = ? AND doc_id = ?",
                (rs, n) -> new Entry(
                        rs.getString("doc_id"), rs.getString("doc_type"), rs.getString("origin"),
                        rs.getString("content_hash"), rs.getString("raw_hash"),
                        rs.getString("embed_model"),
                        (Integer) rs.getObject("profile_version"),
                        toList(rs.getArray("allowed_groups")),
                        rs.getInt("chunk_count")),
                projectId, docId);
        return rows.stream().findFirst();
    }

    public void upsert(long projectId, Entry e) {
        jdbc.update(
                "INSERT INTO document (project_id, doc_id, doc_type, origin, content_hash, raw_hash, " +
                        "embed_model, profile_version, allowed_groups, chunk_count, indexed_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::text[], ?, now()) " +
                        "ON CONFLICT (project_id, doc_id) DO UPDATE SET " +
                        "doc_type = EXCLUDED.doc_type, origin = EXCLUDED.origin, " +
                        "content_hash = EXCLUDED.content_hash, raw_hash = EXCLUDED.raw_hash, " +
                        "embed_model = EXCLUDED.embed_model, profile_version = EXCLUDED.profile_version, " +
                        "allowed_groups = EXCLUDED.allowed_groups, chunk_count = EXCLUDED.chunk_count, " +
                        "indexed_at = now()",
                projectId, e.docId(), e.docType(), e.origin(), e.contentHash(), e.rawHash(),
                e.embedModel(), e.profileVersion(),
                PgVectorRepository.toArrayLiteral(e.allowedGroups()), e.chunkCount());
    }

    public void delete(long projectId, String docId) {
        jdbc.update("DELETE FROM document WHERE project_id = ? AND doc_id = ?", projectId, docId);
    }

    private static List<String> toList(java.sql.Array array) {
        try {
            return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("could not read allowed_groups", e);
        }
    }
}
```

Note: `PgVectorRepository.toArrayLiteral` is package-private and both classes are in `repository`, so this compiles without widening its visibility.

- [ ] **Step 6: Write and run the registry integration test**

```java
@Test
void upsertThenFindRoundTrips() {
    long projectId = projectService.defaultProjectId();
    registry.upsert(projectId, new DocumentRegistry.Entry(
            "REC-1", "invoice", "record", "a".repeat(64), "b".repeat(64),
            "nomic-embed-text", 2, List.of("public"), 7));

    var found = registry.find(projectId, "REC-1").orElseThrow();
    assertThat(found.contentHash()).isEqualTo("a".repeat(64));
    assertThat(found.profileVersion()).isEqualTo(2);
    assertThat(found.allowedGroups()).containsExactly("public");
    assertThat(found.chunkCount()).isEqualTo(7);
}

@Test
void upsertTwiceKeepsOneRow() {
    long projectId = projectService.defaultProjectId();
    DocumentRegistry.Entry e = new DocumentRegistry.Entry(
            "REC-2", "invoice", "record", "a".repeat(64), "b".repeat(64),
            "nomic-embed-text", null, List.of("public"), 1);
    registry.upsert(projectId, e);
    registry.upsert(projectId, e);

    Integer rows = jdbc.queryForObject(
            "SELECT count(*) FROM document WHERE project_id = ? AND doc_id = 'REC-2'",
            Integer.class, projectId);
    assertThat(rows).isEqualTo(1);
}

@Test
void deleteRemovesTheRow() {
    long projectId = projectService.defaultProjectId();
    registry.upsert(projectId, new DocumentRegistry.Entry(
            "REC-3", null, "record", "a".repeat(64), "b".repeat(64),
            "nomic-embed-text", null, List.of("public"), 1));
    registry.delete(projectId, "REC-3");

    assertThat(registry.find(projectId, "REC-3")).isEmpty();
}
```

Run: `./mvnw test "-Dtest=RecordHashTest+DocumentRegistryIntegrationTest"`
Expected: PASS, 5 + 3 tests.

---

## Task 7: Record ingest - the wiring, freshness, and delete depth

**Files:**
- Create: `src/main/java/com/example/springbootrag/service/RecordIngestService.java`
- Create: `src/main/java/com/example/springbootrag/web/RecordController.java`
- Create: `src/main/java/com/example/springbootrag/web/dto/RecordRequest.java`
- Create: `src/main/java/com/example/springbootrag/web/dto/RecordResponse.java`
- Modify: `src/main/java/com/example/springbootrag/service/IngestService.java:183-192`
- Modify: `src/main/java/com/example/springbootrag/repository/DocEdgeRepository.java`
- Test: `src/test/java/com/example/springbootrag/integration/RecordIngestIntegrationTest.java`

**Interfaces:**
- Consumes: `RecordRenderer.render`, `RenderProfile.parse`, `RecordHash`, `DocumentRegistry`, `ProfileRepository`, `IngestService.ingestChunks(...8-arg)`, `EmbeddingProperties.getModel()`.
- Produces:
  - `record RecordRequest(String docId, String docType, JsonNode record, Map<String,Object> metadata, List<String> groups, Boolean force)`
  - `record RecordResponse(String docId, int chunksStored, String status, List<String> warnings)` - status is `indexed`, `metadata-refreshed`, or `skipped`.
  - `RecordResponse RecordIngestService.ingest(long projectId, RecordRequest req)`
  - `void DocEdgeRepository.deleteByDstDoc(long projectId, String docId)`

- [ ] **Step 1: Write the failing integration test**

```java
@Test
void firstIngestStoresChunksAndRegistryRow() {
    long projectId = projectService.defaultProjectId();
    RecordResponse res = recordIngest.ingest(projectId, request("REC-1", INVOICE_JSON));

    assertThat(res.status()).isEqualTo("indexed");
    assertThat(res.chunksStored()).isGreaterThan(0);
    assertThat(registry.find(projectId, "REC-1")).isPresent();
}

@Test
void reIngestingTheSameRecordSkipsWithoutEmbedding() {
    long projectId = projectService.defaultProjectId();
    recordIngest.ingest(projectId, request("REC-2", INVOICE_JSON));
    int callsBefore = fakeEmbeddings.callCount();

    RecordResponse res = recordIngest.ingest(projectId, request("REC-2", INVOICE_JSON));

    assertThat(res.status()).isEqualTo("skipped");
    assertThat(fakeEmbeddings.callCount()).isEqualTo(callsBefore);
}

@Test
void confidenceOnlyChangeRefreshesMetadataWithoutEmbedding() {
    long projectId = projectService.defaultProjectId();
    recordIngest.ingest(projectId, request("REC-3", """
        {"customer":{"value":"ACME","confidence":0.82}}"""));
    int callsBefore = fakeEmbeddings.callCount();

    RecordResponse res = recordIngest.ingest(projectId, request("REC-3", """
        {"customer":{"value":"ACME","confidence":0.93}}"""));

    assertThat(res.status()).isEqualTo("metadata-refreshed");
    assertThat(fakeEmbeddings.callCount()).isEqualTo(callsBefore);

    Double stored = jdbc.queryForObject(
            "SELECT (metadata->'prov'->'customer'->>'confidence')::float " +
            "FROM chunks WHERE doc_id = 'REC-3' LIMIT 1", Double.class);
    assertThat(stored).isEqualTo(0.93);
}

@Test
void changedValueReIndexes() {
    long projectId = projectService.defaultProjectId();
    recordIngest.ingest(projectId, request("REC-4", """{"customer":{"value":"ACME"}}"""));
    RecordResponse res = recordIngest.ingest(projectId,
            request("REC-4", """{"customer":{"value":"OTHER"}}"""));

    assertThat(res.status()).isEqualTo("indexed");
}

@Test
void deleteRemovesChunksFromBothStoresAndTheRegistry() throws Exception {
    long projectId = projectService.defaultProjectId();
    recordIngest.ingest(projectId, request("REC-5", INVOICE_JSON));

    ingestService.delete(projectId, "REC-5");

    Integer pgRows = jdbc.queryForObject(
            "SELECT count(*) FROM chunks WHERE project_id = ? AND doc_id = 'REC-5'",
            Integer.class, projectId);
    assertThat(pgRows).isZero();
    assertThat(registry.find(projectId, "REC-5")).isEmpty();
    assertThat(qdrantPointCount(projectId, "REC-5")).isZero();
}

@Test
void deleteAlsoRemovesInboundEdges() {
    long projectId = projectService.defaultProjectId();
    docEdges.insertLink(projectId, "OTHER-DOC", "REC-6");
    recordIngest.ingest(projectId, request("REC-6", INVOICE_JSON));

    ingestService.delete(projectId, "REC-6");

    // A dangling inbound edge lets graph expansion hop to a document that no longer exists.
    assertThat(docEdges.neighbors(projectId, List.of("OTHER-DOC"))).doesNotContain("REC-6");
}

@Test
void emptyRenderIsRejected() {
    long projectId = projectService.defaultProjectId();
    assertThatThrownBy(() -> recordIngest.ingest(projectId, request("REC-7", """{"note":null}""")))
            .isInstanceOf(IllegalArgumentException.class);
}
```

`INVOICE_JSON` is a nested invoice with two line items and at least one wrapped field. `qdrantPointCount` uses the Qdrant client's scroll/count with a `doc_id` filter - copy the helper style from `AccessControlIntegrationTest`.

- [ ] **Step 2: Run and watch fail**

Run: `./mvnw test "-Dtest=RecordIngestIntegrationTest"`
Expected: compile error - `RecordIngestService` does not exist.

- [ ] **Step 3: Add `deleteByDstDoc` and fix delete depth + ordering**

In `DocEdgeRepository`:

```java
/** Removes edges POINTING AT this doc. Without it, a deleted doc stays reachable by expansion. */
public void deleteByDstDoc(long projectId, String docId) {
    jdbc.update("DELETE FROM doc_edge WHERE project_id = ? AND dst_doc = ?", projectId, docId);
}
```

In `IngestService`, replace `delete(long, String)`:

```java
/**
 * Removes a document from every store that holds part of it.
 *
 * <p>Qdrant goes FIRST on purpose: it is the fallible store, and if it fails the Postgres rows
 * survive so the delete can be retried. The other order loses the rows and orphans the vectors
 * forever (LEARNINGS section 13).
 */
public void delete(long projectId, String docId) {
    try {
        qdrant.deleteByDocId(projectId, docId);
    } catch (ExecutionException | InterruptedException e) {
        throw new IllegalStateException("Qdrant delete failed", e);
    }
    pgVector.deleteByDocId(projectId, docId);
    docEdges.deleteBySrcDoc(projectId, docId);
    docEdges.deleteByDstDoc(projectId, docId);
    documentRegistry.delete(projectId, docId);
    entityRepo.gcOrphanEntities(projectId);
}
```

Add `DocumentRegistry documentRegistry` to the `IngestService` constructor and field list.

- [ ] **Step 4: Implement the DTOs**

```java
package com.example.springbootrag.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** {@code force} re-indexes even when nothing changed. */
public record RecordRequest(String docId, String docType, JsonNode record,
                            Map<String, Object> metadata, List<String> groups, Boolean force) {}
```

```java
package com.example.springbootrag.web.dto;

import java.util.List;

/** {@code status} is one of: indexed, metadata-refreshed, skipped. */
public record RecordResponse(String docId, int chunksStored, String status, List<String> warnings) {}
```

- [ ] **Step 5: Implement RecordIngestService**

```java
package com.example.springbootrag.service;

import com.example.springbootrag.chunk.Chunk;
import com.example.springbootrag.config.EmbeddingProperties;
import com.example.springbootrag.guard.InjectionScanner;
import com.example.springbootrag.record.RecordHash;
import com.example.springbootrag.record.RecordRenderer;
import com.example.springbootrag.record.RenderProfile;
import com.example.springbootrag.record.RenderedBlock;
import com.example.springbootrag.repository.DocumentRegistry;
import com.example.springbootrag.repository.ProfileRepository;
import com.example.springbootrag.repository.PgVectorRepository;
import com.example.springbootrag.repository.QdrantRepository;
import com.example.springbootrag.web.dto.RecordRequest;
import com.example.springbootrag.web.dto.RecordResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Ingests an extracted record: render, hash, decide, store.
 *
 * <p>Two hashes drive three outcomes. Rendered text unchanged and settings unchanged -> skipped.
 * Rendered text unchanged but the raw record changed (a confidence jitter) -> metadata refreshed
 * in place with no embedding call. Anything else -> full re-index.
 */
@Service
public class RecordIngestService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RecordRenderer renderer = new RecordRenderer();
    private final IngestService ingest;
    private final DocumentRegistry registry;
    private final ProfileRepository profiles;
    private final PgVectorRepository pgVector;
    private final QdrantRepository qdrant;
    private final EmbeddingProperties embeddingProps;

    public RecordIngestService(IngestService ingest, DocumentRegistry registry,
                               ProfileRepository profiles, PgVectorRepository pgVector,
                               QdrantRepository qdrant, EmbeddingProperties embeddingProps) {
        this.ingest = ingest;
        this.registry = registry;
        this.profiles = profiles;
        this.pgVector = pgVector;
        this.qdrant = qdrant;
        this.embeddingProps = embeddingProps;
    }

    public RecordResponse ingest(long projectId, RecordRequest req) {
        if (req.docId() == null || req.docId().isBlank()) {
            throw new IllegalArgumentException("docId is required");
        }
        if (req.docType() == null || req.docType().isBlank()) {
            throw new IllegalArgumentException("docType is required");
        }
        if (req.record() == null || !req.record().isObject()) {
            throw new IllegalArgumentException("record must be a JSON object");
        }

        Optional<ProfileRepository.StoredProfile> stored = profiles.find(projectId, req.docType());
        RenderProfile profile = stored.map(p -> RenderProfile.parse(p.body())).orElse(null);
        Integer profileVersion = stored.map(ProfileRepository.StoredProfile::version).orElse(null);

        List<RenderedBlock> blocks = renderer.render(req.record(), profile);
        if (blocks.isEmpty()) {
            // Storing nothing silently is the failure discovered a month later.
            throw new IllegalArgumentException(
                    "record rendered to no text - every field was empty, excluded, or filter-only");
        }

        String contentHash = RecordHash.ofBlocks(blocks);
        String rawHash = RecordHash.ofJson(req.record());
        List<String> groups = req.groups() == null ? List.of() : req.groups();
        boolean force = Boolean.TRUE.equals(req.force());

        Optional<DocumentRegistry.Entry> existing = registry.find(projectId, req.docId());
        if (!force && existing.isPresent()) {
            DocumentRegistry.Entry e = existing.get();
            boolean sameText = e.contentHash().equals(contentHash)
                    && e.embedModel().equals(embeddingProps.getModel())
                    && java.util.Objects.equals(e.profileVersion(), profileVersion)
                    && sameGroups(e.allowedGroups(), groups);
            if (sameText && e.rawHash().equals(rawHash)) {
                return new RecordResponse(req.docId(), e.chunkCount(), "skipped", List.of());
            }
            if (sameText) {
                refreshMetadata(projectId, req, blocks);
                registry.upsert(projectId, new DocumentRegistry.Entry(
                        req.docId(), req.docType(), "record", contentHash, rawHash,
                        e.embedModel(), profileVersion, e.allowedGroups(), e.chunkCount()));
                return new RecordResponse(req.docId(), e.chunkCount(), "metadata-refreshed", List.of());
            }
        }

        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            chunks.add(new Chunk(blocks.get(i).text(), blocks.get(i).breadcrumb(), i));
        }
        // capToBudget can split a block, so build metadata AFTER capping to keep the lists aligned.
        List<Chunk> capped = IngestService.capToBudget(chunks);
        List<String> metadata = metadataFor(capped, blocks, req);

        int storedCount = ingest.ingestChunks(projectId, req.docId(), sourceFileOf(req), capped,
                null, groups, req.docType(), metadata);

        registry.upsert(projectId, new DocumentRegistry.Entry(
                req.docId(), req.docType(), "record", contentHash, rawHash,
                embeddingProps.getModel(), profileVersion,
                groups.isEmpty() ? List.of("public") : groups, storedCount));

        return new RecordResponse(req.docId(), storedCount, "indexed",
                InjectionScanner.scan(joined(blocks)));
    }

    /* ---- helpers ---- */

    /**
     * One metadata JSON per capped chunk: values + prov of the block it came from, the caller's
     * extra metadata, and the chunk-level confidence aggregate. A split block's pieces share the
     * metadata of their parent - each piece is still the same field group.
     */
    private List<String> metadataFor(List<Chunk> capped, List<RenderedBlock> blocks,
                                     RecordRequest req) {
        Map<String, RenderedBlock> byBreadcrumb = new LinkedHashMap<>();
        for (RenderedBlock b : blocks) byBreadcrumb.put(b.breadcrumb(), b);

        List<String> out = new ArrayList<>(capped.size());
        for (Chunk c : capped) {
            RenderedBlock b = byBreadcrumb.get(c.headingPath());
            Map<String, Object> values = new LinkedHashMap<>(b == null ? Map.of() : b.values());
            if (req.metadata() != null) values.putAll(req.metadata());
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("values", values);
            node.put("prov", b == null ? Map.of() : b.prov());
            node.put("conf", confidenceAggregate(b));
            try {
                out.add(MAPPER.writeValueAsString(node));
            } catch (Exception e) {
                throw new IllegalStateException("could not serialise chunk metadata", e);
            }
        }
        return out;
    }

    /** min/avg over NUMERIC confidences only; absent when nothing reported one. */
    private Map<String, Object> confidenceAggregate(RenderedBlock b) {
        if (b == null) return Map.of();
        List<Double> scores = new ArrayList<>();
        for (Object v : b.prov().values()) {
            if (v instanceof Map<?, ?> m && m.get("confidence") instanceof Number n) {
                scores.add(n.doubleValue());
            }
        }
        if (scores.isEmpty()) return Map.of();
        double min = scores.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        double avg = scores.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        return Map.of("min", min, "avg", avg);
    }

    /** Rewrites chunk metadata in both stores without touching vectors. */
    private void refreshMetadata(long projectId, RecordRequest req, List<RenderedBlock> blocks) {
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            chunks.add(new Chunk(blocks.get(i).text(), blocks.get(i).breadcrumb(), i));
        }
        List<Chunk> capped = IngestService.capToBudget(chunks);
        List<String> metadata = metadataFor(capped, blocks, req);
        for (int i = 0; i < capped.size(); i++) {
            pgVector.updateMetadata(projectId, req.docId(), i, metadata.get(i));
            try {
                qdrant.updateMetadata(projectId, req.docId(), i, metadata.get(i));
            } catch (Exception e) {
                throw new IllegalStateException("Qdrant metadata refresh failed", e);
            }
        }
    }

    private static boolean sameGroups(List<String> a, List<String> b) {
        if (b == null || b.isEmpty()) return true;    // request omitted groups: keep what is stored
        return new java.util.HashSet<>(a).equals(new java.util.HashSet<>(b));
    }

    private static String sourceFileOf(RecordRequest req) {
        Object sf = req.metadata() == null ? null : req.metadata().get("sourceFile");
        return sf == null ? null : sf.toString();
    }

    private static String joined(List<RenderedBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (RenderedBlock b : blocks) sb.append(b.text()).append('\\n');
        return sb.toString();
    }
}
```

Add the two metadata-update methods used above:

`PgVectorRepository`:
```java
/** Rewrites one chunk's metadata without touching its vector. */
public void updateMetadata(long projectId, String docId, int chunkIndex, String metadataJson) {
    jdbc.update("UPDATE chunks SET metadata = ?::jsonb " +
                    "WHERE project_id = ? AND doc_id = ? AND chunk_index = ?",
            metadataJson, projectId, docId, chunkIndex);
}
```

`QdrantRepository`:
```java
/** Rewrites the metadata payload keys of one chunk's point, leaving its vector alone. */
public void updateMetadata(long projectId, String docId, int chunkIndex, String metadataJson)
        throws ExecutionException, InterruptedException {
    io.qdrant.client.grpc.Points.SetPayloadPoints request =
            io.qdrant.client.grpc.Points.SetPayloadPoints.newBuilder()
                    .setCollectionName(collection)
                    .putAllPayload(JsonPayload.toQdrant(metadataJson))
                    .setPointsSelector(io.qdrant.client.grpc.Points.PointsSelector.newBuilder()
                            .setFilter(io.qdrant.client.grpc.Points.Filter.newBuilder()
                                    .addMust(match("project_id", projectId))
                                    .addMust(matchKeyword("doc_id", docId))
                                    .addMust(match("chunk_index", (long) chunkIndex))
                                    .build())
                            .build())
                    .setWait(true)
                    .build();
    client.setPayloadAsync(request, java.time.Duration.ofMinutes(1)).get();
}
```

`IngestService.capToBudget` must change from package-private `static` to `public static` so `RecordIngestService` can call it. Update `IngestServiceCapTest` if it referenced it as package-private (it is in a different package already, so it is likely fine).

- [ ] **Step 6: Implement the controller**

```java
package com.example.springbootrag.web;

import com.example.springbootrag.security.CurrentUser;
import com.example.springbootrag.service.IngestService;
import com.example.springbootrag.service.ProjectService;
import com.example.springbootrag.service.RecordIngestService;
import com.example.springbootrag.web.dto.RecordRequest;
import com.example.springbootrag.web.dto.RecordResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
public class RecordController {

    private final RecordIngestService recordIngest;
    private final IngestService ingestService;
    private final ProjectService projectService;
    private final CurrentUser currentUser;

    public RecordController(RecordIngestService recordIngest, IngestService ingestService,
                            ProjectService projectService, CurrentUser currentUser) {
        this.recordIngest = recordIngest;
        this.ingestService = ingestService;
        this.projectService = projectService;
        this.currentUser = currentUser;
    }

    @PostMapping(value = "/projects/{projectId}/records", consumes = MediaType.APPLICATION_JSON_VALUE)
    public RecordResponse ingest(@PathVariable long projectId, @RequestBody RecordRequest req) {
        requireProject(projectId);
        currentUser.requireOwnGroups(req.groups());
        return recordIngest.ingest(projectId, req);
    }

    @DeleteMapping("/projects/{projectId}/records/{docId}")
    public void delete(@PathVariable long projectId, @PathVariable String docId) {
        requireProject(projectId);
        ingestService.delete(projectId, docId);
    }

    private void requireProject(long projectId) {
        if (!projectService.exists(projectId)) {
            throw new IllegalArgumentException("project not found: " + projectId);
        }
    }
}
```

- [ ] **Step 7: Run the tests**

Run: `./mvnw test "-Dtest=RecordIngestIntegrationTest"`
Expected: PASS, 7 tests.

- [ ] **Step 8: Run the full suite**

Run: `./mvnw test`
Expected: all green. `IngestService.delete` changed order and gained steps, so `ProjectDeleteIntegrationTest`, `DocumentIntegrationTest`, and `GraphIngestIntegrationTest` are the ones to watch.

---

## Task 8: Filter DSL - model, SQL, and Qdrant translation

**Files:**
- Create: `src/main/java/com/example/springbootrag/repository/MetadataFilter.java`
- Create: `src/main/java/com/example/springbootrag/repository/FilterSql.java`
- Create: `src/main/java/com/example/springbootrag/repository/FilterQdrant.java`
- Test: `src/test/java/com/example/springbootrag/repository/MetadataFilterTest.java`
- Test: `src/test/java/com/example/springbootrag/repository/FilterSqlTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `record MetadataFilter.Condition(String path, String op, Object value, List<Object> values, Object gte, Object gt, Object lte, Object lt, String type)`
  - `record MetadataFilter(String docType, List<Condition> conditions)` with `static MetadataFilter none()`, `boolean isEmpty()`, `static MetadataFilter parse(String json)`.
  - `record FilterSql.Fragment(String sql, List<Object> args)` and `static Fragment FilterSql.render(MetadataFilter f)` - `sql` is `""` when the filter is empty.
  - `static List<Condition> FilterQdrant.conditions(MetadataFilter f)` returning Qdrant `Points.Condition` values.

- [ ] **Step 1: Write the failing tests**

```java
package com.example.springbootrag.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilterSqlTest {

    @Test
    void emptyFilterProducesNoPredicate() {
        // The single most dangerous case: an empty filter must mean "no filter", never a
        // predicate that matches nothing (LEARNINGS section 13, the empty Qdrant should-clause).
        FilterSql.Fragment f = FilterSql.render(MetadataFilter.none());

        assertThat(f.sql()).isEmpty();
        assertThat(f.args()).isEmpty();
    }

    @Test
    void eqRendersAJsonbTextComparison() {
        FilterSql.Fragment f = FilterSql.render(MetadataFilter.parse("""
            {"filters":[{"path":"values.customer.name","op":"eq","value":"ACME"}]}"""));

        assertThat(f.sql()).isEqualTo(" AND metadata #>> '{values,customer,name}' = ?");
        assertThat(f.args()).containsExactly("ACME");
    }

    @Test
    void docTypeBecomesItsOwnColumnPredicate() {
        FilterSql.Fragment f = FilterSql.render(MetadataFilter.parse("""
            {"docType":"invoice"}"""));

        assertThat(f.sql()).isEqualTo(" AND doc_type = ?");
        assertThat(f.args()).containsExactly("invoice");
    }

    @Test
    void inRendersOnePlaceholderPerValue() {
        FilterSql.Fragment f = FilterSql.render(MetadataFilter.parse("""
            {"filters":[{"path":"values.status","op":"in","values":["open","overdue"]}]}"""));

        assertThat(f.sql()).isEqualTo(" AND metadata #>> '{values,status}' IN (?,?)");
        assertThat(f.args()).containsExactly("open", "overdue");
    }

    @Test
    void numberRangeCastsBothSides() {
        FilterSql.Fragment f = FilterSql.render(MetadataFilter.parse("""
            {"filters":[{"path":"conf.min","op":"range","gte":0.7,"type":"number"}]}"""));

        assertThat(f.sql()).isEqualTo(" AND (metadata #>> '{conf,min}')::numeric >= ?");
        assertThat(f.args()).containsExactly(0.7);
    }

    @Test
    void dateRangeCastsToTimestamp() {
        FilterSql.Fragment f = FilterSql.render(MetadataFilter.parse("""
            {"filters":[{"path":"values.issueDate","op":"range",
                         "gte":"2026-04-01","lt":"2026-07-01","type":"date"}]}"""));

        assertThat(f.sql()).contains("::timestamptz >= ?").contains("::timestamptz < ?");
        assertThat(f.args()).containsExactly("2026-04-01", "2026-07-01");
    }

    @Test
    void existsChecksForANonNullPath() {
        FilterSql.Fragment f = FilterSql.render(MetadataFilter.parse("""
            {"filters":[{"path":"values.approvedBy","op":"exists"}]}"""));

        assertThat(f.sql()).isEqualTo(" AND metadata #>> '{values,approvedBy}' IS NOT NULL");
    }

    @Test
    void multipleConditionsAndTogether() {
        FilterSql.Fragment f = FilterSql.render(MetadataFilter.parse("""
            {"docType":"invoice",
             "filters":[{"path":"values.status","op":"eq","value":"open"},
                        {"path":"values.total","op":"range","gt":100,"type":"number"}]}"""));

        assertThat(f.sql()).startsWith(" AND doc_type = ?");
        assertThat(f.args()).containsExactly("invoice", "open", 100);
    }

    @Test
    void pathIsValidatedAgainstInjection() {
        // Paths are interpolated into a #>> '{...}' literal, so they can never contain a quote,
        // brace, or backslash. Anything else is a caller bug, not data variance.
        assertThatThrownBy(() -> FilterSql.render(MetadataFilter.parse("""
                {"filters":[{"path":"values.a'} , '{b","op":"exists"}]}""")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void arrayMarkersAreStrippedFromPaths() {
        FilterSql.Fragment f = FilterSql.render(MetadataFilter.parse("""
            {"filters":[{"path":"values.lineItems[].sku","op":"eq","value":"A-1"}]}"""));

        assertThat(f.sql()).isEqualTo(" AND metadata #>> '{values,lineItems,sku}' = ?");
    }
}
```

And validation tests:

```java
package com.example.springbootrag.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetadataFilterTest {

    @Test
    void nullOrBlankJsonIsAnEmptyFilter() {
        assertThat(MetadataFilter.parse(null).isEmpty()).isTrue();
        assertThat(MetadataFilter.parse("  ").isEmpty()).isTrue();
    }

    @Test
    void unknownOpIsRejected() {
        assertThatThrownBy(() -> MetadataFilter.parse("""
                {"filters":[{"path":"values.a","op":"regex","value":"x"}]}"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("regex");
    }

    @Test
    void rangeWithoutABoundIsRejected() {
        assertThatThrownBy(() -> MetadataFilter.parse("""
                {"filters":[{"path":"values.a","op":"range"}]}"""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inWithAnEmptyListIsRejected() {
        assertThatThrownBy(() -> MetadataFilter.parse("""
                {"filters":[{"path":"values.a","op":"in","values":[]}]}"""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void docTypeOnlyFilterIsNotEmpty() {
        assertThat(MetadataFilter.parse("""{"docType":"invoice"}""").isEmpty()).isFalse();
    }
}
```

- [ ] **Step 2: Run and watch fail**

Run: `./mvnw test "-Dtest=FilterSqlTest+MetadataFilterTest"`
Expected: compile error - `MetadataFilter` does not exist.

- [ ] **Step 3: Implement MetadataFilter**

```java
package com.example.springbootrag.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Structured narrowing over chunk metadata. Never a substitute for the access-label predicate:
 * a filter is a user preference, a label is a boundary.
 */
public record MetadataFilter(String docType, List<Condition> conditions) {

    public record Condition(String path, String op, Object value, List<Object> values,
                            Object gte, Object gt, Object lte, Object lt, String type) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> OPS = Set.of("eq", "in", "range", "exists");

    public static MetadataFilter none() {
        return new MetadataFilter(null, List.of());
    }

    public boolean isEmpty() {
        return (docType == null || docType.isBlank()) && conditions.isEmpty();
    }

    /** Accepts null/blank as "no filter". Malformed conditions are caller bugs: 400, not silence. */
    public static MetadataFilter parse(String json) {
        if (json == null || json.isBlank()) return none();
        try {
            JsonNode root = MAPPER.readTree(json);
            String docType = root.hasNonNull("docType") ? root.get("docType").asText() : null;
            List<Condition> out = new ArrayList<>();
            JsonNode filters = root.get("filters");
            if (filters != null && filters.isArray()) {
                for (JsonNode f : filters) out.add(condition(f));
            }
            return new MetadataFilter(docType, List.copyOf(out));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("filters is not valid JSON", e);
        }
    }

    private static Condition condition(JsonNode f) {
        String path = f.hasNonNull("path") ? f.get("path").asText() : null;
        String op = f.hasNonNull("op") ? f.get("op").asText() : null;
        if (path == null || path.isBlank()) throw new IllegalArgumentException("filter path is required");
        if (op == null || !OPS.contains(op)) throw new IllegalArgumentException("unknown filter op: " + op);

        List<Object> values = new ArrayList<>();
        if (f.has("values")) f.get("values").forEach(v -> values.add(scalar(v)));
        if ("in".equals(op) && values.isEmpty()) {
            throw new IllegalArgumentException("op 'in' needs a non-empty values list");
        }
        Object gte = f.has("gte") ? scalar(f.get("gte")) : null;
        Object gt = f.has("gt") ? scalar(f.get("gt")) : null;
        Object lte = f.has("lte") ? scalar(f.get("lte")) : null;
        Object lt = f.has("lt") ? scalar(f.get("lt")) : null;
        if ("range".equals(op) && gte == null && gt == null && lte == null && lt == null) {
            throw new IllegalArgumentException("op 'range' needs at least one bound");
        }
        Object value = f.has("value") ? scalar(f.get("value")) : null;
        if ("eq".equals(op) && value == null) {
            throw new IllegalArgumentException("op 'eq' needs a value");
        }
        String type = f.hasNonNull("type") ? f.get("type").asText() : "text";
        return new Condition(path, op, value, List.copyOf(values), gte, gt, lte, lt, type);
    }

    private static Object scalar(JsonNode n) {
        if (n.isNumber()) return n.numberValue();
        if (n.isBoolean()) return n.booleanValue();
        return n.asText();
    }
}
```

- [ ] **Step 4: Implement FilterSql**

```java
package com.example.springbootrag.repository;

import java.util.ArrayList;
import java.util.List;

/** Renders a {@link MetadataFilter} into a SQL fragment appended INSIDE the retrieval query. */
public final class FilterSql {

    public record Fragment(String sql, List<Object> args) {
        public static Fragment empty() { return new Fragment("", List.of()); }
    }

    private FilterSql() {}

    public static Fragment render(MetadataFilter filter) {
        if (filter == null || filter.isEmpty()) return Fragment.empty();
        StringBuilder sql = new StringBuilder();
        List<Object> args = new ArrayList<>();

        if (filter.docType() != null && !filter.docType().isBlank()) {
            sql.append(" AND doc_type = ?");
            args.add(filter.docType());
        }
        for (MetadataFilter.Condition c : filter.conditions()) {
            String accessor = accessor(c.path());
            switch (c.op()) {
                case "eq" -> { sql.append(" AND ").append(accessor).append(" = ?"); args.add(c.value()); }
                case "in" -> {
                    sql.append(" AND ").append(accessor).append(" IN (")
                       .append(DocFilter.placeholders(c.values().size())).append(")");
                    args.addAll(c.values());
                }
                case "exists" -> sql.append(" AND ").append(accessor).append(" IS NOT NULL");
                case "range" -> {
                    String typed = cast(accessor, c.type());
                    if (c.gte() != null) { sql.append(" AND ").append(typed).append(" >= ?"); args.add(c.gte()); }
                    if (c.gt() != null)  { sql.append(" AND ").append(typed).append(" > ?");  args.add(c.gt()); }
                    if (c.lte() != null) { sql.append(" AND ").append(typed).append(" <= ?"); args.add(c.lte()); }
                    if (c.lt() != null)  { sql.append(" AND ").append(typed).append(" < ?");  args.add(c.lt()); }
                }
                default -> throw new IllegalArgumentException("unknown filter op: " + c.op());
            }
        }
        return new Fragment(sql.toString(), args);
    }

    /** {@code values.customer.name} -> {@code metadata #>> '{values,customer,name}'}. */
    static String accessor(String path) {
        return "metadata #>> '{" + String.join(",", segments(path)) + "}'";
    }

    /**
     * Paths are interpolated into a literal, never bound, so they are validated hard. Array
     * markers are dropped: an array element is its own chunk carrying its own scalars.
     */
    static List<String> segments(String path) {
        List<String> out = new ArrayList<>();
        for (String raw : path.split("\\\\.")) {
            String seg = raw.replace("[]", "").trim();
            if (seg.isEmpty()) continue;
            if (!seg.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException("illegal filter path segment: " + raw);
            }
            out.add(seg);
        }
        if (out.isEmpty()) throw new IllegalArgumentException("empty filter path");
        return out;
    }

    private static String cast(String accessor, String type) {
        return switch (type == null ? "text" : type) {
            case "number" -> "(" + accessor + ")::numeric";
            case "date" -> "(" + accessor + ")::timestamptz";
            default -> accessor;
        };
    }
}
```

- [ ] **Step 5: Implement FilterQdrant**

```java
package com.example.springbootrag.repository;

import io.qdrant.client.grpc.Points.Condition;
import io.qdrant.client.grpc.Points.Filter;

import java.util.ArrayList;
import java.util.List;

import static io.qdrant.client.ConditionFactory.match;
import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.ConditionFactory.range;

/**
 * Same filter, Qdrant dialect. Payload keys are nested, so a dotted path maps straight onto
 * Qdrant's own path syntax - which is exactly why metadata is stored nested rather than as flat
 * dotted keys: Qdrant would read the dot inside a literal key as a path separator.
 */
public final class FilterQdrant {

    private FilterQdrant() {}

    public static List<Condition> conditions(MetadataFilter filter) {
        List<Condition> out = new ArrayList<>();
        if (filter == null || filter.isEmpty()) return out;

        if (filter.docType() != null && !filter.docType().isBlank()) {
            out.add(matchKeyword("doc_type", filter.docType()));
        }
        for (MetadataFilter.Condition c : filter.conditions()) {
            String key = String.join(".", FilterSql.segments(c.path()));
            switch (c.op()) {
                case "eq" -> out.add(matchValue(key, c.value()));
                case "in" -> {
                    Filter.Builder any = Filter.newBuilder();
                    for (Object v : c.values()) any.addShould(matchValue(key, v));
                    out.add(Condition.newBuilder().setFilter(any.build()).build());
                }
                case "exists" -> out.add(Condition.newBuilder()
                        .setIsNull(io.qdrant.client.grpc.Points.IsNullCondition.newBuilder()
                                .setKey(key).build())
                        .build());   // inverted below
                case "range" -> {
                    io.qdrant.client.grpc.Points.Range.Builder r =
                            io.qdrant.client.grpc.Points.Range.newBuilder();
                    if (c.gte() instanceof Number n) r.setGte(n.doubleValue());
                    if (c.gt() instanceof Number n) r.setGt(n.doubleValue());
                    if (c.lte() instanceof Number n) r.setLte(n.doubleValue());
                    if (c.lt() instanceof Number n) r.setLt(n.doubleValue());
                    out.add(range(key, r.build()));
                }
                default -> throw new IllegalArgumentException("unknown filter op: " + c.op());
            }
        }
        return out;
    }

    private static Condition matchValue(String key, Object v) {
        if (v instanceof Number n) return match(key, n.longValue());
        if (v instanceof Boolean b) return match(key, b);
        return matchKeyword(key, String.valueOf(v));
    }
}
```

**Note on `exists`:** Qdrant has `IsNull`, which is the opposite of what `exists` means. Build it as a `must_not` on the caller side - Task 9's `QdrantRepository.search` puts `exists` conditions into `addMustNot(isNull(key))` instead of `addMust(...)`. Keep that split explicit: `FilterQdrant.conditions` returns must-conditions, and add a second method `FilterQdrant.mustNotConditions(MetadataFilter)` returning the `isNull` conditions for `exists` paths. Adjust the `case "exists"` branch above to skip must-conditions accordingly.

**Note on `range` with dates:** Qdrant `Range` is numeric only. A date range filter on the Qdrant backend is therefore unsupported for now - `FilterQdrant` throws `IllegalArgumentException("date range is not supported on the qdrant backend")` for `type=date`, and Task 9's integration test asserts that message rather than pretending the filter applied. Record this limitation in `docs/implementation-notes.md`; storing dates as epoch numbers is the follow-up if it matters.

- [ ] **Step 6: Run the tests**

Run: `./mvnw test "-Dtest=FilterSqlTest+MetadataFilterTest"`
Expected: PASS, 15 tests total.

---

## Task 9: Thread the filter through all six backends

**Files:**
- Modify: `src/main/java/com/example/springbootrag/repository/PgFtsRepository.java:28-60`
- Modify: `src/main/java/com/example/springbootrag/repository/PgVectorRepository.java:45-74`
- Modify: `src/main/java/com/example/springbootrag/repository/QdrantRepository.java:115-171`
- Modify: `src/main/java/com/example/springbootrag/service/SearchService.java`
- Test: `src/test/java/com/example/springbootrag/integration/MetadataFilterIntegrationTest.java`

**Interfaces:**
- Consumes: `MetadataFilter`, `FilterSql.render`, `FilterQdrant.conditions`.
- Produces (each old signature stays and delegates with `MetadataFilter.none()`):
  - `PgFtsRepository.search(SearchContext, String query, int topK, List<Long> projectIds, List<String> docIds, MetadataFilter filter)`
  - `PgVectorRepository.search(SearchContext, float[] queryEmbedding, int topK, List<Long>, List<String>, MetadataFilter)`
  - `QdrantRepository.search(SearchContext, float[], int, List<Long>, List<String>, MetadataFilter)`
  - `SearchService.search(SearchContext, String type, String query, int topK, List<Long>, List<String>, MetadataFilter)`
  - `SearchService.compare(SearchContext, String query, int topK, List<Long>, List<String>, MetadataFilter)`
  - `SearchService.searchTraced(SearchContext, String type, String query, int topK, List<Long>, List<String>, MetadataFilter)`

- [ ] **Step 1: Write the failing integration test**

```java
@Test
void filterNarrowsResultsOnEveryBackend() {
    // Two records, same words, different customer.
    ingestInvoice("REC-A", "ACME", "late payment reminder");
    ingestInvoice("REC-B", "GLOBEX", "late payment reminder");

    MetadataFilter f = MetadataFilter.parse("""
        {"docType":"invoice",
         "filters":[{"path":"values.customer","op":"eq","value":"ACME"}]}""");

    for (String type : List.of("fts", "pgvector", "qdrant", "hybrid", "rerank", "graph")) {
        List<SearchHit> hits = searchService.search(ctx, type, "late payment", 10,
                List.of(projectId), List.of(), f);
        assertThat(hits).as("backend %s", type)
                .isNotEmpty()
                .allMatch(h -> h.docId().equals("REC-A"));
    }
}

@Test
void pgvectorAndQdrantAgreeUnderTheSameFilter() {
    ingestInvoice("REC-C", "ACME", "unpaid balance");
    ingestInvoice("REC-D", "GLOBEX", "unpaid balance");
    MetadataFilter f = MetadataFilter.parse("""
        {"filters":[{"path":"values.customer","op":"eq","value":"GLOBEX"}]}""");

    List<String> pg = searchService.search(ctx, "pgvector", "unpaid", 10, List.of(projectId), List.of(), f)
            .stream().map(SearchHit::docId).toList();
    List<String> qd = searchService.search(ctx, "qdrant", "unpaid", 10, List.of(projectId), List.of(), f)
            .stream().map(SearchHit::docId).toList();

    assertThat(pg).containsExactlyInAnyOrderElementsOf(qd);
}

@Test
void filterAppliesBeforeTheRerankerOverFetchTrims() {
    // 60 decoys of the WRONG customer (over app.rerank.candidates=50) plus one match. If the
    // filter ran after the over-fetch, the match would be trimmed away and this returns empty.
    for (int i = 0; i < 60; i++) ingestInvoice("DECOY-" + i, "GLOBEX", "quarterly statement");
    ingestInvoice("NEEDLE", "ACME", "quarterly statement");

    MetadataFilter f = MetadataFilter.parse("""
        {"filters":[{"path":"values.customer","op":"eq","value":"ACME"}]}""");
    List<SearchHit> hits = searchService.search(ctx, "rerank", "quarterly statement", 10,
            List.of(projectId), List.of(), f);

    assertThat(hits).isNotEmpty();
    assertThat(hits).allMatch(h -> h.docId().equals("NEEDLE"));
}

@Test
void graphExpansionCannotReturnANeighbourThatFailsTheFilter() {
    ingestInvoice("SEED", "ACME", "shipping terms");
    ingestInvoice("NEIGHBOUR", "GLOBEX", "shipping terms appendix");
    docEdges.insertLink(projectId, "SEED", "NEIGHBOUR");

    MetadataFilter f = MetadataFilter.parse("""
        {"filters":[{"path":"values.customer","op":"eq","value":"ACME"}]}""");
    List<SearchHit> hits = searchService.search(ctx, "graph", "shipping terms", 10,
            List.of(projectId), List.of(), f);

    assertThat(hits).noneMatch(h -> h.docId().equals("NEIGHBOUR"));
}

@Test
void emptyFilterReturnsEverythingReadable() {
    ingestInvoice("REC-E", "ACME", "delivery note");
    ingestInvoice("REC-F", "GLOBEX", "delivery note");

    List<SearchHit> hits = searchService.search(ctx, "hybrid", "delivery note", 10,
            List.of(projectId), List.of(), MetadataFilter.none());

    assertThat(hits).extracting(SearchHit::docId).contains("REC-E", "REC-F");
}

@Test
void filterCannotWidenPastAccessLabels() {
    ingestInvoiceWithGroups("SECRET", "ACME", "restricted terms", List.of("finance"));

    MetadataFilter f = MetadataFilter.parse("""
        {"filters":[{"path":"values.customer","op":"eq","value":"ACME"}]}""");
    // ctxPublicOnly holds only the 'public' group.
    List<SearchHit> hits = searchService.search(ctxPublicOnly, "hybrid", "restricted terms", 10,
            List.of(projectId), List.of(), f);

    assertThat(hits).isEmpty();
}

@Test
void confidenceFilterNarrowsAndAbsenceOfItDoesNot() {
    ingestInvoiceWithConfidence("LOW-CONF", "ACME", "payment schedule", 0.30);
    ingestInvoiceWithConfidence("HIGH-CONF", "ACME", "payment schedule", 0.95);

    MetadataFilter f = MetadataFilter.parse("""
        {"filters":[{"path":"conf.min","op":"range","gte":0.7,"type":"number"}]}""");

    assertThat(searchService.search(ctx, "pgvector", "payment schedule", 10,
            List.of(projectId), List.of(), f))
            .extracting(SearchHit::docId).containsOnly("HIGH-CONF");

    assertThat(searchService.search(ctx, "pgvector", "payment schedule", 10,
            List.of(projectId), List.of(), MetadataFilter.none()))
            .extracting(SearchHit::docId).contains("LOW-CONF", "HIGH-CONF");
}

@Test
void dateRangeOnQdrantFailsLoudlyRatherThanSilently() {
    MetadataFilter f = MetadataFilter.parse("""
        {"filters":[{"path":"values.issueDate","op":"range","gte":"2026-01-01","type":"date"}]}""");

    assertThatThrownBy(() -> searchService.search(ctx, "qdrant", "anything", 10,
            List.of(projectId), List.of(), f))
            .hasMessageContaining("date range is not supported");
}
```

- [ ] **Step 2: Run and watch fail**

Run: `./mvnw test "-Dtest=MetadataFilterIntegrationTest"`
Expected: compile error - `search` has no 6-arg overload taking a filter.

- [ ] **Step 3: Add the filter to the Postgres repositories**

In `PgVectorRepository.search`, keep the 5-arg overload delegating with `MetadataFilter.none()` and add the filter to the new one:

```java
public List<SearchHit> search(SearchContext ctx, float[] queryEmbedding, int topK,
                              List<Long> projectIds, List<String> docIds) {
    return search(ctx, queryEmbedding, topK, projectIds, docIds, MetadataFilter.none());
}

public List<SearchHit> search(SearchContext ctx, float[] queryEmbedding, int topK,
                              List<Long> projectIds, List<String> docIds, MetadataFilter filter) {
    StringBuilder where = new StringBuilder(" WHERE" + DocFilter.groupClause(ctx.groups()));
    List<Object> args = new ArrayList<>();
    args.add(toVectorLiteral(queryEmbedding));
    args.addAll(ctx.groups());
    if (DocFilter.active(projectIds)) {
        where.append(" AND project_id IN (").append(DocFilter.placeholders(projectIds.size())).append(")");
        args.addAll(projectIds);
    }
    if (DocFilter.active(docIds)) {
        where.append(" AND doc_id IN (").append(DocFilter.placeholders(docIds.size())).append(")");
        args.addAll(docIds);
    }
    FilterSql.Fragment f = FilterSql.render(filter);
    where.append(f.sql());
    args.addAll(f.args());
    args.add(topK);
    // ... unchanged query body ...
}
```

Do the same in `PgFtsRepository.search` - append `f.sql()` after the existing `docClause` and `f.args()` before the trailing `topK` argument. **Argument order must match placeholder order**; the FTS query binds the query text twice at the front, so append the filter args after the doc-filter args and before `topK`.

- [ ] **Step 4: Add the filter to Qdrant**

In `QdrantRepository.search(..., MetadataFilter filter)`, after the existing group/project/doc conditions:

```java
for (io.qdrant.client.grpc.Points.Condition c : FilterQdrant.conditions(filter)) {
    filterBuilder.addMust(c);
}
for (io.qdrant.client.grpc.Points.Condition c : FilterQdrant.mustNotConditions(filter)) {
    filterBuilder.addMustNot(c);
}
```

- [ ] **Step 5: Thread it through SearchService**

Add the filter as the last parameter of `search`, `searchTraced`, `compare`, and the private `hybrid`, `rerank`, `graph`, `qdrantSearch` helpers. Existing public overloads delegate with `MetadataFilter.none()`. Two spots matter:

```java
private List<SearchHit> rerank(SearchContext ctx, String query, float[] queryEmbedding, int topK,
                               List<Long> projectIds, List<String> docIds, MetadataFilter filter) {
    // The over-fetch is ALREADY filtered - filtering after the trim would drop matching documents.
    List<SearchHit> candidates = hybrid(ctx, query, queryEmbedding,
            rerankProps.getCandidates(), projectIds, docIds, filter);
    return reranker.rerank(query, candidates, topK);
}
```

```java
// inside graph(...), the structural branch:
for (SearchHit h : pgVector.chunksByDocIds(ctx, projectId, neighborDocs, filter)) {
    byId.putIfAbsent(h.id(), h);
}
// and the semantic branch:
for (SearchHit h : pgVector.chunksByIds(ctx, chunkIds, filter)) {
    byId.putIfAbsent(h.id(), h);
}
```

That needs filter-aware overloads of `chunksByDocIds` and `chunksByIds` too - same pattern: append `FilterSql.render(filter)` to the WHERE clause and its args to the argument list. An expanded neighbour that fails the filter must not come back, exactly as access labels already work.

- [ ] **Step 6: Run the tests**

Run: `./mvnw test "-Dtest=MetadataFilterIntegrationTest"`
Expected: PASS, 8 tests.

- [ ] **Step 7: Run the full suite**

Run: `./mvnw test`
Expected: all green. Every existing caller uses the old overloads, so nothing else should move.

---

## Task 10: Expose filters on the HTTP surface

**Files:**
- Modify: `src/main/java/com/example/springbootrag/web/SearchController.java`
- Modify: `src/main/java/com/example/springbootrag/web/dto/ChatRequest.java`
- Modify: `src/main/java/com/example/springbootrag/service/ChatService.java`
- Modify: `src/main/java/com/example/springbootrag/service/AskService.java`
- Test: `src/test/java/com/example/springbootrag/web/SearchControllerFilterTest.java`

**Interfaces:**
- Consumes: `MetadataFilter.parse`, the Task 9 `SearchService` overloads.
- Produces: `/search` and `/compare` accept `docType` and `filters` (a JSON string) query parameters; `ChatRequest` gains `String docType` and `String filters`.

- [ ] **Step 1: Write the failing controller test**

Follow the `@WebMvcTest` + `@MockBean` pattern already used in `src/test/java/com/example/springbootrag/web/FeedbackControllerTest.java`:

```java
@Test
void filtersParamIsParsedAndPassedThrough() throws Exception {
    mockMvc.perform(get("/search")
                    .param("q", "late payment")
                    .param("docType", "invoice")
                    .param("filters", """
                        {"filters":[{"path":"values.customer","op":"eq","value":"ACME"}]}""")
                    .with(user("alice").roles("USER")))
            .andExpect(status().isOk());

    ArgumentCaptor<MetadataFilter> captor = ArgumentCaptor.forClass(MetadataFilter.class);
    verify(searchService).search(any(), eq("hybrid"), eq("late payment"), eq(10),
            anyList(), anyList(), captor.capture());

    assertThat(captor.getValue().docType()).isEqualTo("invoice");
    assertThat(captor.getValue().conditions()).hasSize(1);
}

@Test
void noFilterParamsMeansAnEmptyFilterNotNull() throws Exception {
    mockMvc.perform(get("/search").param("q", "anything").with(user("alice").roles("USER")))
            .andExpect(status().isOk());

    ArgumentCaptor<MetadataFilter> captor = ArgumentCaptor.forClass(MetadataFilter.class);
    verify(searchService).search(any(), anyString(), anyString(), anyInt(),
            anyList(), anyList(), captor.capture());

    assertThat(captor.getValue().isEmpty()).isTrue();
}

@Test
void malformedFilterJsonIsABadRequest() throws Exception {
    mockMvc.perform(get("/search").param("q", "x").param("filters", "{not json")
                    .with(user("alice").roles("USER")))
            .andExpect(status().isBadRequest());
}
```

- [ ] **Step 2: Run and watch fail**

Run: `./mvnw test "-Dtest=SearchControllerFilterTest"`
Expected: FAIL - the controller has no `filters` parameter.

- [ ] **Step 3: Implement the controller change**

```java
@GetMapping("/search")
public List<SearchHit> search(@RequestParam String q,
                              @RequestParam(defaultValue = "hybrid") String type,
                              @RequestParam(defaultValue = "10") int topK,
                              @RequestParam(required = false) List<String> docIds,
                              @RequestParam(required = false) Long projectId,
                              @RequestParam(defaultValue = "false") boolean group,
                              @RequestParam(required = false) String docType,
                              @RequestParam(required = false) String filters) {
    List<Long> scope = projectService.resolveScope(projectId, group);
    return searchService.search(currentUser.context(), type, q, topK, scope,
            docIds == null ? List.of() : docIds, metadataFilter(docType, filters));
}

/** docType is a convenience shortcut for the same field inside the filters JSON. */
private static MetadataFilter metadataFilter(String docType, String filters) {
    MetadataFilter parsed = MetadataFilter.parse(filters);
    if (docType == null || docType.isBlank()) return parsed;
    return new MetadataFilter(docType, parsed.conditions());
}
```

Mirror it in `/compare`. `MetadataFilter.parse` already throws `IllegalArgumentException` on bad JSON, and `GlobalExceptionHandler` already maps that to 400 - confirm by reading `web/GlobalExceptionHandler.java` before assuming it.

- [ ] **Step 4: Thread the filter through chat and ask**

Add `String docType` and `String filters` to `ChatRequest`, build a `MetadataFilter` in `ChatService` and `AskService`, and pass it to the `SearchService` call each one makes. Retrieval in the chat path runs on the condensed query, but the filter is caller-supplied and applies unchanged.

- [ ] **Step 5: Run the tests**

Run: `./mvnw test "-Dtest=SearchControllerFilterTest"`
Expected: PASS, 3 tests.

- [ ] **Step 6: Run the full suite**

Run: `./mvnw test`
Expected: all green, including `ChatControllerTest` and `AskServiceTest`.

---

## Task 11: Live verification and documentation

**Files:**
- Modify: `docs/implementation-notes.md`
- Modify: `docs/LEARNINGS.md`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/RAG-MASTERY.md`
- Modify: `README.md`

- [ ] **Step 1: Start the app and ingest two record types**

Run: `./mvnw spring-boot:run` (server on :8085, every call needs basic auth).

Ingest at least two records of `docType=invoice` and two of a second type with a different schema and no profile, including wrapped fields with confidence and grounding. Use `curl -u alice:alice`.

- [ ] **Step 2: Verify the three ingest outcomes by hand**

Re-post an identical record - expect `"status":"skipped"`. Re-post with only a confidence changed - expect `"status":"metadata-refreshed"`. Re-post with a changed value - expect `"status":"indexed"`.

- [ ] **Step 3: Measure filtered vs unfiltered retrieval**

Run the same question through `/compare` with and without a filter, then read the `retrieve` stage from `GET /traces` for each. Record both numbers.

- [ ] **Step 4: Write the docs**

- `docs/implementation-notes.md`: the nested `values`/`prov`/`conf` shape and why (Qdrant dot parsing); the two-hash design; the Qdrant date-range limitation; anything that deviated from this plan.
- `docs/LEARNINGS.md`: a new section on filtered retrieval - the enforcement rules that go wrong quietly (post-filtering, over-fetch ordering, empty filter matching nothing) and the measured retrieve-stage numbers from Step 3.
- `docs/ARCHITECTURE.md`: the record ingest path and where the filter is enforced in each backend.
- `docs/RAG-MASTERY.md`: re-score section 9 row 2 (ingestion failure modes) and row 4 (query understanding), with an honest note on what is still missing.
- `README.md`: `POST /projects/{id}/records`, `DELETE /projects/{id}/records/{docId}`, the profile endpoints, and the `docType`/`filters` parameters on `/search` and `/compare`.

- [ ] **Step 5: Final full suite**

Run: `./mvnw test`
Expected: 188 pre-existing tests plus roughly 60 new ones, 0 failures, 3 skipped (manual DJL).

---

## Self-Review Notes

- **Spec coverage:** section 1 -> Task 7; section 2 -> Tasks 2, 3; section 2.1/2.2 -> Tasks 2, 3, 7; section 3 -> Tasks 4, 5; section 4 -> Tasks 1, 8, 9, 10; section 5 -> Tasks 6, 7; section 6 -> all; section 7 -> Tasks 7, 8 (error cases are asserted in the tests of each); section 8 -> every task's tests plus Task 11; section 9 -> Task 11.
- **Known gap accepted:** the spec's "unknown filter path returns a warning" is implemented as "matches nothing" only. Surfacing per-request filter warnings needs a response envelope change on `/search`, which would break the existing UI contract. Left out deliberately - note it in `docs/implementation-notes.md` at Task 11.
- **Known gap accepted:** `RenderProfile.isBoundary` is parsed and tested but not yet consumed by `RecordRenderer`, which uses generic boundaries only. Wiring it is a small follow-up; the profile field is stored so no migration is needed later.
