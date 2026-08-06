package com.example.springbootrag.integration;

import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.repository.DocumentRegistry;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "app.graph.edges=structural")
@Testcontainers
class DocumentRegistryIntegrationTest {

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

    @Autowired DocumentRegistry registry;
    @Autowired ProjectService projectService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void upsertThenFindRoundTrips() {
        long projectId = projectService.defaultProjectId();
        registry.upsert(projectId, new DocumentRegistry.Entry(
                "REC-1", "invoice", "record", "a".repeat(64), "b".repeat(64),
                "nomic-embed-text", 2, List.of("public"), 7));

        var found = registry.find(projectId, "REC-1").orElseThrow();
        assertThat(found.contentHash()).isEqualTo("a".repeat(64));
        assertThat(found.rawHash()).isEqualTo("b".repeat(64));
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
    void nullProfileVersionStaysNull() {
        long projectId = projectService.defaultProjectId();
        registry.upsert(projectId, new DocumentRegistry.Entry(
                "REC-4", null, "record", "a".repeat(64), "b".repeat(64),
                "nomic-embed-text", null, List.of("public"), 1));

        // Null must survive the round trip: 0 would look like "profile version zero" and make a
        // freshly profiled document look unchanged.
        assertThat(registry.find(projectId, "REC-4").orElseThrow().profileVersion()).isNull();
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
}
