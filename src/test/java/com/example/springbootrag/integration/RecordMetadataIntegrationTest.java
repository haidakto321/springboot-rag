package com.example.springbootrag.integration;

import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.repository.PgVectorRepository;
import com.example.springbootrag.service.IngestService;
import com.example.springbootrag.service.ProjectService;
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

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Record metadata (doc_type + the values/prov/conf trees) survives the round trip to both stores. */
@SpringBootTest(properties = "app.graph.edges=structural")
@Testcontainers
class RecordMetadataIntegrationTest {

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

    @Autowired PgVectorRepository pgVector;
    @Autowired IngestService ingestService;
    @Autowired ProjectService projectService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void metadataRoundTripsThroughPostgres() {
        long projectId = projectService.defaultProjectId();
        String meta = """
                {"values":{"customer":{"name":"ACME"}},"prov":{},"conf":{"min":0.9,"avg":0.9}}""";

        long id = pgVector.insert(projectId, "REC-1", 0, "Customer: ACME", "REC-1.json",
                "customer", vec(), null, List.of("public"), "invoice", meta);

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

        ingestService.delete(projectId, "MD-1");
    }

    @Test
    void perChunkMetadataListMustMatchChunkCount() {
        long projectId = projectService.defaultProjectId();
        // A mismatched list would attach one field group's provenance to another's text.
        assertThat(catchThrowable(() -> ingestService.ingestChunks(
                projectId, "MD-2", null,
                List.of(new com.example.springbootrag.chunk.Chunk("a", null, 0),
                        new com.example.springbootrag.chunk.Chunk("b", null, 1)),
                null, List.of("public"), "invoice", List.of("{}"))))
                .isInstanceOf(IllegalStateException.class);
    }

    private static Throwable catchThrowable(Runnable r) {
        try {
            r.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private static float[] vec() {
        float[] v = new float[768];
        Arrays.fill(v, 0.1f);
        return v;
    }
}
