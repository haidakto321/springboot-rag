# springboot-rag Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Java/Spring Boot sandbox that ingests text and searches it through four backends (Postgres FTS, pgvector, Qdrant, hybrid RRF) so the techniques can be compared side by side.

**Architecture:** Layered Spring Boot app (controller -> service -> repository). One `EmbeddingProvider` interface (Ollama impl) turns text into vectors. Three repositories each own one search backend; `SearchService` runs one or all and fuses keyword+vector with Reciprocal Rank Fusion. Postgres accessed with raw `JdbcTemplate` to keep SQL visible; Qdrant via its Java client.

**Tech Stack:** Java 21, Spring Boot 3.3, Maven, PostgreSQL + pgvector, Qdrant, Ollama (`nomic-embed-text`, 768d), JUnit 5, Testcontainers.

> **Git note (user rule):** This user never runs `git add` / `git commit`. Every "Checkpoint" step is a manual stop where the USER commits. Do NOT run git commands.

> **Implementation notes (user rule):** Keep a running `docs/implementation-notes.md` with decisions, deviations from this plan, and tradeoffs.

---

## File Structure

```
springboot-rag/
├── pom.xml
├── docker-compose.yml
├── README.md
├── docs/
│   ├── 2026-06-13-springboot-rag-design.md   (spec, already written)
│   ├── plans/2026-06-13-springboot-rag.md     (this file)
│   └── implementation-notes.md                (running notes)
└── src/
    ├── main/
    │   ├── java/com/example/springbootrag/
    │   │   ├── SpringbootRagApplication.java
    │   │   ├── config/
    │   │   │   ├── EmbeddingProperties.java
    │   │   │   └── QdrantConfig.java
    │   │   ├── embedding/
    │   │   │   ├── EmbeddingProvider.java
    │   │   │   └── OllamaEmbeddingProvider.java
    │   │   ├── chunk/
    │   │   │   └── Chunker.java
    │   │   ├── fusion/
    │   │   │   └── RrfFusion.java
    │   │   ├── model/
    │   │   │   └── SearchHit.java
    │   │   ├── repository/
    │   │   │   ├── PgFtsRepository.java
    │   │   │   ├── PgVectorRepository.java
    │   │   │   └── QdrantRepository.java
    │   │   ├── service/
    │   │   │   ├── IngestService.java
    │   │   │   └── SearchService.java
    │   │   └── web/
    │   │       ├── IngestController.java
    │   │       ├── SearchController.java
    │   │       └── dto/ (IngestRequest, IngestResponse, CompareResponse)
    │   └── resources/
    │       ├── application.yml
    │       └── schema.sql
    └── test/
        └── java/com/example/springbootrag/
            ├── chunk/ChunkerTest.java
            ├── fusion/RrfFusionTest.java
            ├── embedding/OllamaEmbeddingProviderTest.java
            └── integration/SearchIntegrationTest.java
```

---

## Task 0: Project scaffold

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/example/springbootrag/SpringbootRagApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `docs/implementation-notes.md`

- [ ] **Step 1: Create `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.5</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>springboot-rag</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>springboot-rag</name>

    <properties>
        <java.version>21</java.version>
        <qdrant.client.version>1.12.0</qdrant.client.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.qdrant</groupId>
            <artifactId>client</artifactId>
            <version>${qdrant.client.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
            <version>2.6.0</version>
        </dependency>

        <!-- test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>qdrant</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.testcontainers</groupId>
                <artifactId>testcontainers-bom</artifactId>
                <version>1.20.4</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create the application class**

`src/main/java/com/example/springbootrag/SpringbootRagApplication.java`
```java
package com.example.springbootrag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SpringbootRagApplication {
    public static void main(String[] args) {
        SpringApplication.run(SpringbootRagApplication.class, args);
    }
}
```

- [ ] **Step 3: Create `application.yml`**

`src/main/resources/application.yml`
```yaml
spring:
  application:
    name: springboot-rag
  datasource:
    url: jdbc:postgresql://localhost:5432/ragdb
    username: rag
    password: rag
  sql:
    init:
      mode: always   # run schema.sql on startup

app:
  embedding:
    provider: ollama
    model: nomic-embed-text
    dimension: 768
  ollama:
    base-url: http://localhost:11434
  qdrant:
    host: localhost
    port: 6334
    collection: chunks

management:
  endpoints:
    web:
      exposure:
        include: health
```

- [ ] **Step 4: Create `docs/implementation-notes.md`**

```markdown
# Implementation notes - springboot-rag

Running log of decisions, deviations, and tradeoffs.

- 2026-06-13: Project scaffolded from plan. Build tool Maven, Spring Boot 3.3.5, Java 21.
```

- [ ] **Step 5: Verify it compiles and boots context**

Run: `mvn -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Checkpoint (USER commits)**

Tell the user: scaffold ready, please review and commit. Do not run git.

---

## Task 1: Infrastructure (docker-compose + README)

**Files:**
- Create: `docker-compose.yml`
- Create: `README.md`

- [ ] **Step 1: Create `docker-compose.yml`**

```yaml
services:
  postgres:
    image: pgvector/pgvector:pg16
    environment:
      POSTGRES_DB: ragdb
      POSTGRES_USER: rag
      POSTGRES_PASSWORD: rag
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data

  qdrant:
    image: qdrant/qdrant:latest
    ports:
      - "6333:6333"   # REST
      - "6334:6334"   # gRPC (used by the Java client)
    volumes:
      - qdrantdata:/qdrant/storage

volumes:
  pgdata:
  qdrantdata:
```

- [ ] **Step 2: Create `README.md`**

````markdown
# springboot-rag

Self-study sandbox comparing Postgres FTS, pgvector, Qdrant, and hybrid (RRF) search in Java.
See `docs/2026-06-13-springboot-rag-design.md` for the design.

## Prerequisites
- Java 21, Maven
- Docker + Docker Compose
- Ollama: install, then `ollama pull nomic-embed-text` and `ollama serve`

## Run
```bash
docker compose up -d            # postgres + qdrant
ollama serve                    # if not already running
mvn spring-boot:run
```
Swagger UI: http://localhost:8080/swagger-ui.html

## Endpoints
- `POST /ingest` - ingest a document
- `GET /search?q=...&type=fts|pgvector|qdrant|hybrid&topK=10`
- `GET /compare?q=...&topK=10` - all backends side by side
- `DELETE /docs/{docId}`
- `GET /actuator/health`
````

- [ ] **Step 3: Bring up infra and verify**

Run: `docker compose up -d && docker compose ps`
Expected: `postgres` and `qdrant` both `running`/healthy.

- [ ] **Step 4: Checkpoint (USER commits)**

---

## Task 2: Domain model - `SearchHit`

**Files:**
- Create: `src/main/java/com/example/springbootrag/model/SearchHit.java`

- [ ] **Step 1: Create the record**

```java
package com.example.springbootrag.model;

/** One search result row, shared by every backend. */
public record SearchHit(
        long id,
        String docId,
        int chunkIndex,
        String content,
        double score
) {}
```

- [ ] **Step 2: Verify compile**

Run: `mvn -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Checkpoint (USER commits)**

---

## Task 3: Chunker (TDD)

**Files:**
- Create: `src/main/java/com/example/springbootrag/chunk/Chunker.java`
- Test: `src/test/java/com/example/springbootrag/chunk/ChunkerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.example.springbootrag.chunk;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ChunkerTest {

    @Test
    void splitsByWordCountWithOverlap() {
        Chunker chunker = new Chunker(5, 2); // 5 words/chunk, 2 overlap
        String text = "one two three four five six seven eight";
        List<String> chunks = chunker.chunk(text);

        assertThat(chunks).containsExactly(
                "one two three four five",
                "four five six seven eight"
        );
    }

    @Test
    void shortTextIsOneChunk() {
        Chunker chunker = new Chunker(5, 2);
        assertThat(chunker.chunk("only three words")).containsExactly("only three words");
    }

    @Test
    void blankTextYieldsNoChunks() {
        Chunker chunker = new Chunker(5, 2);
        assertThat(chunker.chunk("   ")).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=ChunkerTest test`
Expected: FAIL (Chunker does not exist).

- [ ] **Step 3: Implement `Chunker`**

```java
package com.example.springbootrag.chunk;

import java.util.ArrayList;
import java.util.List;

/** Splits text into overlapping word windows. */
public class Chunker {

    private final int windowWords;
    private final int overlapWords;

    public Chunker(int windowWords, int overlapWords) {
        if (overlapWords >= windowWords) {
            throw new IllegalArgumentException("overlap must be smaller than window");
        }
        this.windowWords = windowWords;
        this.overlapWords = overlapWords;
    }

    public List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        String[] words = text.trim().split("\\s+");
        int step = windowWords - overlapWords;
        for (int start = 0; start < words.length; start += step) {
            int end = Math.min(start + windowWords, words.length);
            chunks.add(String.join(" ", java.util.Arrays.copyOfRange(words, start, end)));
            if (end == words.length) {
                break;
            }
        }
        return chunks;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=ChunkerTest test`
Expected: PASS (3 tests).

- [ ] **Step 5: Checkpoint (USER commits)**

---

## Task 4: RRF fusion (TDD)

**Files:**
- Create: `src/main/java/com/example/springbootrag/fusion/RrfFusion.java`
- Test: `src/test/java/com/example/springbootrag/fusion/RrfFusionTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.example.springbootrag.fusion;

import com.example.springbootrag.model.SearchHit;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class RrfFusionTest {

    private SearchHit hit(long id) {
        return new SearchHit(id, "doc", (int) id, "c" + id, 0.0);
    }

    @Test
    void documentRankedHighInBothListsWinsTop() {
        // list A order: 1,2,3   list B order: 2,1,4
        List<SearchHit> a = List.of(hit(1), hit(2), hit(3));
        List<SearchHit> b = List.of(hit(2), hit(1), hit(4));

        List<SearchHit> fused = new RrfFusion(60).fuse(List.of(a, b), 10);

        // id 2 appears at rank0 (B) and rank1 (A); id 1 at rank0 (A) and rank1 (B).
        // Both have equal RRF, but id with higher single best rank ties; assert both lead.
        assertThat(fused).extracting(SearchHit::id).startsWith(2L, 1L);
        assertThat(fused).extracting(SearchHit::id).contains(3L, 4L);
    }

    @Test
    void scoreIsSumOfReciprocalRanks() {
        List<SearchHit> a = List.of(hit(1)); // rank 0
        List<SearchHit> fused = new RrfFusion(60).fuse(List.of(a), 10);
        assertThat(fused.get(0).score()).isEqualTo(1.0 / (60 + 1));
    }

    @Test
    void respectsTopK() {
        List<SearchHit> a = List.of(hit(1), hit(2), hit(3));
        assertThat(new RrfFusion(60).fuse(List.of(a), 2)).hasSize(2);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=RrfFusionTest test`
Expected: FAIL (RrfFusion does not exist).

- [ ] **Step 3: Implement `RrfFusion`**

```java
package com.example.springbootrag.fusion;

import com.example.springbootrag.model.SearchHit;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reciprocal Rank Fusion: score = sum over lists of 1/(k + rank). */
public class RrfFusion {

    private final int k;

    public RrfFusion(int k) {
        this.k = k;
    }

    public List<SearchHit> fuse(List<List<SearchHit>> rankedLists, int topK) {
        Map<Long, Double> scores = new LinkedHashMap<>();
        Map<Long, SearchHit> byId = new LinkedHashMap<>();

        for (List<SearchHit> list : rankedLists) {
            for (int rank = 0; rank < list.size(); rank++) {
                SearchHit hit = list.get(rank);
                scores.merge(hit.id(), 1.0 / (k + rank + 1), Double::sum);
                byId.putIfAbsent(hit.id(), hit);
            }
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> {
                    SearchHit h = byId.get(e.getKey());
                    return new SearchHit(h.id(), h.docId(), h.chunkIndex(), h.content(), e.getValue());
                })
                .toList();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=RrfFusionTest test`
Expected: PASS (3 tests).

- [ ] **Step 5: Checkpoint (USER commits)**

---

## Task 5: Embedding config + provider interface

**Files:**
- Create: `src/main/java/com/example/springbootrag/config/EmbeddingProperties.java`
- Create: `src/main/java/com/example/springbootrag/embedding/EmbeddingProvider.java`

- [ ] **Step 1: Create `EmbeddingProperties`**

```java
package com.example.springbootrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.embedding")
public class EmbeddingProperties {
    private String provider = "ollama";
    private String model = "nomic-embed-text";
    private int dimension = 768;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getDimension() { return dimension; }
    public void setDimension(int dimension) { this.dimension = dimension; }
}
```

- [ ] **Step 2: Create the interface**

```java
package com.example.springbootrag.embedding;

/** Turns text into a vector. Swap implementations (Ollama now, Azure later) without touching callers. */
public interface EmbeddingProvider {
    float[] embed(String text);
    int dimension();
}
```

- [ ] **Step 3: Enable config properties**

Modify `SpringbootRagApplication.java` - add annotation:
```java
package com.example.springbootrag;

import com.example.springbootrag.config.EmbeddingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(EmbeddingProperties.class)
public class SpringbootRagApplication {
    public static void main(String[] args) {
        SpringApplication.run(SpringbootRagApplication.class, args);
    }
}
```

- [ ] **Step 4: Verify compile**

Run: `mvn -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Checkpoint (USER commits)**

---

## Task 6: Ollama embedding provider (TDD with mock HTTP)

**Files:**
- Create: `src/main/java/com/example/springbootrag/embedding/OllamaEmbeddingProvider.java`
- Test: `src/test/java/com/example/springbootrag/embedding/OllamaEmbeddingProviderTest.java`

- [ ] **Step 1: Write the failing test (MockWebServer)**

Add test dependency to `pom.xml` (in `<dependencies>`):
```xml
<dependency>
    <groupId>com.squareup.okhttp3</groupId>
    <artifactId>mockwebserver</artifactId>
    <version>4.12.0</version>
    <scope>test</scope>
</dependency>
```

Test:
```java
package com.example.springbootrag.embedding;

import com.example.springbootrag.config.EmbeddingProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class OllamaEmbeddingProviderTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void parsesEmbeddingFromOllamaResponse() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"embedding\":[0.1,0.2,0.3]}"));

        EmbeddingProperties props = new EmbeddingProperties();
        props.setDimension(3);
        RestClient client = RestClient.builder()
                .baseUrl(server.url("/").toString())
                .build();

        OllamaEmbeddingProvider provider = new OllamaEmbeddingProvider(client, props);
        float[] vec = provider.embed("hello");

        assertThat(vec).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(provider.dimension()).isEqualTo(3);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=OllamaEmbeddingProviderTest test`
Expected: FAIL (OllamaEmbeddingProvider does not exist).

- [ ] **Step 3: Implement the provider**

```java
package com.example.springbootrag.embedding;

import com.example.springbootrag.config.EmbeddingProperties;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

public class OllamaEmbeddingProvider implements EmbeddingProvider {

    private final RestClient client;
    private final EmbeddingProperties props;

    public OllamaEmbeddingProvider(RestClient client, EmbeddingProperties props) {
        this.client = client;
        this.props = props;
    }

    @Override
    public float[] embed(String text) {
        OllamaResponse resp = client.post()
                .uri("/api/embeddings")
                .body(Map.of("model", props.getModel(), "prompt", text))
                .retrieve()
                .body(OllamaResponse.class);

        if (resp == null || resp.embedding() == null) {
            throw new IllegalStateException("Ollama returned no embedding");
        }
        List<Double> e = resp.embedding();
        float[] out = new float[e.size()];
        for (int i = 0; i < e.size(); i++) {
            out[i] = e.get(i).floatValue();
        }
        return out;
    }

    @Override
    public int dimension() {
        return props.getDimension();
    }

    private record OllamaResponse(List<Double> embedding) {}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=OllamaEmbeddingProviderTest test`
Expected: PASS.

- [ ] **Step 5: Wire the bean**

Create `src/main/java/com/example/springbootrag/config/EmbeddingConfig.java`:
```java
package com.example.springbootrag.config;

import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.embedding.OllamaEmbeddingProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class EmbeddingConfig {

    @Bean
    public EmbeddingProvider embeddingProvider(EmbeddingProperties props,
                                               @Value("${app.ollama.base-url}") String baseUrl) {
        RestClient client = RestClient.builder().baseUrl(baseUrl).build();
        return new OllamaEmbeddingProvider(client, props);
    }
}
```

- [ ] **Step 6: Verify compile**

Run: `mvn -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Checkpoint (USER commits)**

---

## Task 7: Postgres schema

**Files:**
- Create: `src/main/resources/schema.sql`

- [ ] **Step 1: Create `schema.sql`**

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS chunks (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    doc_id      VARCHAR(255) NOT NULL,
    chunk_index INT NOT NULL,
    content     TEXT NOT NULL,
    tsv         tsvector GENERATED ALWAYS AS (to_tsvector('english', content)) STORED,
    embedding   vector(768) NOT NULL,
    created_at  TIMESTAMP DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_chunks_tsv ON chunks USING gin (tsv);
CREATE INDEX IF NOT EXISTS idx_chunks_embedding ON chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_chunks_doc_id ON chunks (doc_id);
```

> Note: dimension `768` matches `app.embedding.dimension`. If you switch to a 1536-dim
> provider later, change this DDL and re-ingest.

- [ ] **Step 2: Verify schema applies on boot**

Run: `docker compose up -d` then `mvn -q spring-boot:run` (Ctrl-C after it starts).
Expected: app starts with no SQL error; `chunks` table exists (`docker compose exec postgres psql -U rag -d ragdb -c "\d chunks"`).

- [ ] **Step 3: Checkpoint (USER commits)**

---

## Task 8: PgVectorRepository

**Files:**
- Create: `src/main/java/com/example/springbootrag/repository/PgVectorRepository.java`

- [ ] **Step 1: Implement the repository**

```java
package com.example.springbootrag.repository;

import com.example.springbootrag.model.SearchHit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class PgVectorRepository {

    private final JdbcTemplate jdbc;

    public PgVectorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts one chunk and returns its generated id. */
    public long insert(String docId, int chunkIndex, String content, float[] embedding) {
        return jdbc.queryForObject(
                "INSERT INTO chunks (doc_id, chunk_index, content, embedding) " +
                        "VALUES (?, ?, ?, ?::vector) RETURNING id",
                Long.class,
                docId, chunkIndex, content, toVectorLiteral(embedding));
    }

    /** Vector search: lower cosine distance = more similar, so we sort ascending and invert to a score. */
    public List<SearchHit> search(float[] queryEmbedding, int topK) {
        return jdbc.query(
                "SELECT id, doc_id, chunk_index, content, " +
                        "       embedding <=> ?::vector AS distance " +
                        "FROM chunks ORDER BY distance ASC LIMIT ?",
                (rs, rowNum) -> new SearchHit(
                        rs.getLong("id"),
                        rs.getString("doc_id"),
                        rs.getInt("chunk_index"),
                        rs.getString("content"),
                        1.0 - rs.getDouble("distance")),
                toVectorLiteral(queryEmbedding), topK);
    }

    public void deleteByDocId(String docId) {
        jdbc.update("DELETE FROM chunks WHERE doc_id = ?", docId);
    }

    /** pgvector text format: "[0.1,0.2,0.3]". */
    static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `mvn -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Checkpoint (USER commits)**

> Behavior is covered by the integration test in Task 13.

---

## Task 9: PgFtsRepository

**Files:**
- Create: `src/main/java/com/example/springbootrag/repository/PgFtsRepository.java`

- [ ] **Step 1: Implement the repository**

```java
package com.example.springbootrag.repository;

import com.example.springbootrag.model.SearchHit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class PgFtsRepository {

    private final JdbcTemplate jdbc;

    public PgFtsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Postgres native full-text search ranked by ts_rank. */
    public List<SearchHit> search(String query, int topK) {
        return jdbc.query(
                "SELECT id, doc_id, chunk_index, content, " +
                        "       ts_rank(tsv, plainto_tsquery('english', ?)) AS rank " +
                        "FROM chunks " +
                        "WHERE tsv @@ plainto_tsquery('english', ?) " +
                        "ORDER BY rank DESC LIMIT ?",
                (rs, rowNum) -> new SearchHit(
                        rs.getLong("id"),
                        rs.getString("doc_id"),
                        rs.getInt("chunk_index"),
                        rs.getString("content"),
                        rs.getDouble("rank")),
                query, query, topK);
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `mvn -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Checkpoint (USER commits)**

---

## Task 10: Qdrant config + repository

**Files:**
- Create: `src/main/java/com/example/springbootrag/config/QdrantConfig.java`
- Create: `src/main/java/com/example/springbootrag/repository/QdrantRepository.java`

- [ ] **Step 1: Create `QdrantConfig` (client bean + ensure collection)**

```java
package com.example.springbootrag.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QdrantConfig {

    @Bean
    public QdrantClient qdrantClient(@Value("${app.qdrant.host}") String host,
                                     @Value("${app.qdrant.port}") int port) {
        return new QdrantClient(
                QdrantGrpcClient.newBuilder(host, port, false).build());
    }
}
```

- [ ] **Step 2: Implement `QdrantRepository` (creates collection on startup, upsert, search, delete)**

```java
package com.example.springbootrag.repository;

import com.example.springbootrag.config.EmbeddingProperties;
import com.example.springbootrag.model.SearchHit;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.CreateCollection;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.JsonWithInt.Value;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;
import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.PointsFactory.filter;

@Repository
public class QdrantRepository {

    private final QdrantClient client;
    private final EmbeddingProperties props;
    private final String collection;

    public QdrantRepository(QdrantClient client,
                            EmbeddingProperties props,
                            @org.springframework.beans.factory.annotation.Value("${app.qdrant.collection}") String collection) {
        this.client = client;
        this.props = props;
        this.collection = collection;
    }

    @PostConstruct
    public void ensureCollection() throws ExecutionException, InterruptedException {
        boolean exists = client.collectionExistsAsync(collection).get();
        if (!exists) {
            client.createCollectionAsync(CreateCollection.newBuilder()
                    .setCollectionName(collection)
                    .setVectorsConfig(VectorsConfig.newBuilder()
                            .setParams(VectorParams.newBuilder()
                                    .setSize(props.getDimension())
                                    .setDistance(Distance.Cosine)
                                    .build())
                            .build())
                    .build()).get();
        }
    }

    public void upsert(long id, String docId, int chunkIndex, String content, float[] embedding)
            throws ExecutionException, InterruptedException {
        PointStruct point = PointStruct.newBuilder()
                .setId(id(id))
                .setVectors(vectors(embedding))
                .putAllPayload(Map.of(
                        "doc_id", value(docId),
                        "chunk_index", value(chunkIndex),
                        "content", value(content)))
                .build();
        client.upsertAsync(collection, List.of(point)).get();
    }

    public List<SearchHit> search(float[] queryEmbedding, int topK)
            throws ExecutionException, InterruptedException {
        List<Float> vec = new ArrayList<>(queryEmbedding.length);
        for (float f : queryEmbedding) vec.add(f);

        List<ScoredPoint> points = client.searchAsync(SearchPoints.newBuilder()
                .setCollectionName(collection)
                .addAllVector(vec)
                .setLimit(topK)
                .setWithPayload(io.qdrant.client.WithPayloadSelectorFactory.enable(true))
                .build()).get();

        List<SearchHit> hits = new ArrayList<>();
        for (ScoredPoint p : points) {
            Map<String, Value> payload = p.getPayloadMap();
            hits.add(new SearchHit(
                    p.getId().getNum(),
                    payload.get("doc_id").getStringValue(),
                    (int) payload.get("chunk_index").getIntegerValue(),
                    payload.get("content").getStringValue(),
                    p.getScore()));
        }
        return hits;
    }

    public void deleteByDocId(String docId) throws ExecutionException, InterruptedException {
        client.deleteAsync(collection, filter(matchKeyword("doc_id", docId))).get();
    }
}
```

> Note: Qdrant static-factory helper names (`id`, `value`, `vectors`, `matchKeyword`,
> `filter`, `enable`) are from `io.qdrant.client` factories. If an import name differs in
> the resolved client version, fix the import - the call shape stays the same. Record any
> such deviation in `docs/implementation-notes.md`.

- [ ] **Step 3: Verify compile**

Run: `mvn -q compile`
Expected: BUILD SUCCESS. If a Qdrant factory import does not resolve, adjust the import to the matching helper in the installed `io.qdrant:client` version and note it.

- [ ] **Step 4: Checkpoint (USER commits)**

---

## Task 11: IngestService

**Files:**
- Create: `src/main/java/com/example/springbootrag/service/IngestService.java`

- [ ] **Step 1: Implement the service**

```java
package com.example.springbootrag.service;

import com.example.springbootrag.chunk.Chunker;
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
    private final Chunker chunker = new Chunker(120, 20);

    public IngestService(EmbeddingProvider embeddings,
                         PgVectorRepository pgVector,
                         QdrantRepository qdrant) {
        this.embeddings = embeddings;
        this.pgVector = pgVector;
        this.qdrant = qdrant;
    }

    public int ingest(String docId, String text) {
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("docId is required");
        }
        List<String> chunks = chunker.chunk(text);
        int index = 0;
        for (String chunk : chunks) {
            float[] vec = embeddings.embed(chunk);
            long id = pgVector.insert(docId, index, chunk, vec);
            try {
                qdrant.upsert(id, docId, index, chunk, vec);
            } catch (ExecutionException | InterruptedException e) {
                throw new IllegalStateException("Qdrant upsert failed", e);
            }
            index++;
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

- [ ] **Step 2: Verify compile**

Run: `mvn -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Checkpoint (USER commits)**

---

## Task 12: SearchService

**Files:**
- Create: `src/main/java/com/example/springbootrag/service/SearchService.java`

- [ ] **Step 1: Implement the service**

```java
package com.example.springbootrag.service;

import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.fusion.RrfFusion;
import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.repository.PgFtsRepository;
import com.example.springbootrag.repository.PgVectorRepository;
import com.example.springbootrag.repository.QdrantRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Service
public class SearchService {

    private final EmbeddingProvider embeddings;
    private final PgFtsRepository fts;
    private final PgVectorRepository pgVector;
    private final QdrantRepository qdrant;
    private final RrfFusion rrf = new RrfFusion(60);

    public SearchService(EmbeddingProvider embeddings,
                         PgFtsRepository fts,
                         PgVectorRepository pgVector,
                         QdrantRepository qdrant) {
        this.embeddings = embeddings;
        this.fts = fts;
        this.pgVector = pgVector;
        this.qdrant = qdrant;
    }

    public List<SearchHit> search(String type, String query, int topK) {
        return switch (type) {
            case "fts" -> fts.search(query, topK);
            case "pgvector" -> pgVector.search(embeddings.embed(query), topK);
            case "qdrant" -> qdrantSearch(query, topK);
            case "hybrid" -> hybrid(query, topK);
            default -> throw new IllegalArgumentException("unknown type: " + type);
        };
    }

    /** Runs each backend once, returns timing + results keyed by backend name. */
    public Map<String, BackendResult> compare(String query, int topK) {
        Map<String, BackendResult> out = new LinkedHashMap<>();
        for (String type : List.of("fts", "pgvector", "qdrant", "hybrid")) {
            long start = System.nanoTime();
            List<SearchHit> hits = search(type, query, topK);
            long ms = (System.nanoTime() - start) / 1_000_000;
            out.put(type, new BackendResult(hits, ms));
        }
        return out;
    }

    private List<SearchHit> hybrid(String query, int topK) {
        List<SearchHit> keyword = fts.search(query, topK);
        List<SearchHit> vector = pgVector.search(embeddings.embed(query), topK);
        return rrf.fuse(List.of(keyword, vector), topK);
    }

    private List<SearchHit> qdrantSearch(String query, int topK) {
        try {
            return qdrant.search(embeddings.embed(query), topK);
        } catch (ExecutionException | InterruptedException e) {
            throw new IllegalStateException("Qdrant search failed", e);
        }
    }

    public record BackendResult(List<SearchHit> hits, long elapsedMs) {}
}
```

- [ ] **Step 2: Verify compile**

Run: `mvn -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Checkpoint (USER commits)**

---

## Task 13: Web layer (DTOs + controllers)

**Files:**
- Create: `src/main/java/com/example/springbootrag/web/dto/IngestRequest.java`
- Create: `src/main/java/com/example/springbootrag/web/dto/IngestResponse.java`
- Create: `src/main/java/com/example/springbootrag/web/IngestController.java`
- Create: `src/main/java/com/example/springbootrag/web/SearchController.java`

- [ ] **Step 1: Create DTOs**

`IngestRequest.java`
```java
package com.example.springbootrag.web.dto;

public record IngestRequest(String docId, String text) {}
```

`IngestResponse.java`
```java
package com.example.springbootrag.web.dto;

public record IngestResponse(String docId, int chunksStored) {}
```

- [ ] **Step 2: Create `IngestController`**

```java
package com.example.springbootrag.web;

import com.example.springbootrag.service.IngestService;
import com.example.springbootrag.web.dto.IngestRequest;
import com.example.springbootrag.web.dto.IngestResponse;
import org.springframework.web.bind.annotation.*;

@RestController
public class IngestController {

    private final IngestService ingestService;

    public IngestController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping("/ingest")
    public IngestResponse ingest(@RequestBody IngestRequest req) {
        int stored = ingestService.ingest(req.docId(), req.text());
        return new IngestResponse(req.docId(), stored);
    }

    @DeleteMapping("/docs/{docId}")
    public void delete(@PathVariable String docId) {
        ingestService.delete(docId);
    }
}
```

- [ ] **Step 3: Create `SearchController`**

```java
package com.example.springbootrag.web;

import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.service.SearchService;
import com.example.springbootrag.service.SearchService.BackendResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/search")
    public List<SearchHit> search(@RequestParam String q,
                                  @RequestParam(defaultValue = "hybrid") String type,
                                  @RequestParam(defaultValue = "10") int topK) {
        return searchService.search(type, q, topK);
    }

    @GetMapping("/compare")
    public Map<String, BackendResult> compare(@RequestParam String q,
                                              @RequestParam(defaultValue = "10") int topK) {
        return searchService.compare(q, topK);
    }
}
```

- [ ] **Step 4: Verify compile + boot**

Run: `mvn -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Checkpoint (USER commits)**

---

## Task 14: Integration test (Testcontainers, end to end)

**Files:**
- Create: `src/test/java/com/example/springbootrag/integration/SearchIntegrationTest.java`

This test uses real Postgres+pgvector and Qdrant containers, and a **fake**
`EmbeddingProvider` (deterministic vectors) so it does not need Ollama running.

- [ ] **Step 1: Write the test**

```java
package com.example.springbootrag.integration;

import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.service.IngestService;
import com.example.springbootrag.service.SearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.qdrant.QdrantContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class SearchIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("ragdb").withUsername("rag").withPassword("rag");

    @Container
    static QdrantContainer qdrant =
            new QdrantContainer(DockerImageName.parse("qdrant/qdrant:latest"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.qdrant.host", qdrant::getHost);
        registry.add("app.qdrant.port", qdrant::getGrpcPort);
        registry.add("app.embedding.dimension", () -> "3");
    }

    /** Deterministic fake embeddings so the test does not need Ollama. */
    @TestConfiguration
    static class FakeEmbeddingConfig {
        @Bean
        @Primary
        EmbeddingProvider fakeEmbeddingProvider() {
            return new EmbeddingProvider() {
                @Override public float[] embed(String text) {
                    String t = text.toLowerCase();
                    // axis 0 = "pressure" topic, axis 1 = "invoice" topic, axis 2 = bias
                    float pressure = t.contains("pressure") || t.contains("seepage") || t.contains("leak") ? 1f : 0f;
                    float invoice = t.contains("invoice") || t.contains("payment") ? 1f : 0f;
                    return new float[]{pressure, invoice, 0.1f};
                }
                @Override public int dimension() { return 3; }
            };
        }
    }

    @Autowired IngestService ingestService;
    @Autowired SearchService searchService;

    @Test
    void ingestsAndSearchesAcrossBackends() {
        ingestService.ingest("doc1", "hydraulic seepage caused a pressure drop on line 3");
        ingestService.ingest("doc2", "the invoice payment was overdue by thirty days");

        // FTS keyword: exact word "invoice" finds doc2
        List<SearchHit> fts = searchService.search("fts", "invoice", 10);
        assertThat(fts).isNotEmpty();
        assertThat(fts.get(0).docId()).isEqualTo("doc2");

        // pgvector semantic: "machine lost pressure" maps to pressure axis -> doc1
        List<SearchHit> vec = searchService.search("pgvector", "machine lost pressure", 10);
        assertThat(vec.get(0).docId()).isEqualTo("doc1");

        // qdrant semantic: same expectation
        List<SearchHit> qd = searchService.search("qdrant", "machine lost pressure", 10);
        assertThat(qd.get(0).docId()).isEqualTo("doc1");

        // hybrid returns results
        assertThat(searchService.search("hybrid", "pressure", 10)).isNotEmpty();

        // compare returns all four backends with timing
        var cmp = searchService.compare("pressure", 5);
        assertThat(cmp.keySet()).containsExactly("fts", "pgvector", "qdrant", "hybrid");
        assertThat(cmp.get("fts").elapsedMs()).isGreaterThanOrEqualTo(0);
    }
}
```

- [ ] **Step 2: Run the integration test**

Run: `mvn -q -Dtest=SearchIntegrationTest test`
Expected: PASS. (Docker must be available; Testcontainers pulls the images on first run.)

- [ ] **Step 3: Checkpoint (USER commits)**

---

## Task 15: Manual end-to-end smoke (with real Ollama)

**Files:** none (manual verification).

- [ ] **Step 1: Start everything**

Run:
```bash
docker compose up -d
ollama pull nomic-embed-text
ollama serve   # if not already running
mvn spring-boot:run
```

- [ ] **Step 2: Ingest two documents**

Run:
```bash
curl -X POST localhost:8080/ingest -H 'Content-Type: application/json' \
  -d '{"docId":"d1","text":"hydraulic seepage caused a pressure drop on line 3 incident 42"}'
curl -X POST localhost:8080/ingest -H 'Content-Type: application/json' \
  -d '{"docId":"d2","text":"invoice INV-2024-08812 payment overdue thirty days"}'
```
Expected: each returns `{"docId":...,"chunksStored":>=1}`.

- [ ] **Step 3: Compare backends on a paraphrase query**

Run: `curl 'localhost:8080/compare?q=machine%20lost%20pressure&topK=5'`
Expected: `pgvector`/`qdrant`/`hybrid` rank `d1` first; `fts` may return nothing (no exact word match) - this is the lesson.

- [ ] **Step 4: Compare on an exact code**

Run: `curl 'localhost:8080/compare?q=INV-2024-08812&topK=5'`
Expected: `fts` finds `d2`; record observations in `docs/implementation-notes.md`.

- [ ] **Step 5: Checkpoint (USER commits)**

---

## Self-Review (completed by plan author)

- **Spec coverage:** Purpose/compare (Tasks 12-15), stack (Task 0-1), pluggable embeddings (Tasks 5-6), data model Postgres (Task 7) + Qdrant (Task 10), all endpoints (Tasks 13), hybrid RRF (Tasks 4,12), infra (Task 1), testing (Task 14). Non-goals respected (no auth/multi-tenant/rerank/UI).
- **Placeholders:** none - every code step has complete code. The one open variability (Qdrant factory import names across client versions) is called out explicitly with a fix instruction, not left vague.
- **Type consistency:** `SearchHit` shape consistent across all repos/services; `EmbeddingProvider` (`embed`, `dimension`) consistent in provider, config, services, test fake; `RrfFusion.fuse(List<List<SearchHit>>, int)` used consistently; `SearchService.BackendResult` used by controller + test.
```
