package com.example.springbootrag.integration;

import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.service.IngestService;
import com.example.springbootrag.service.ProjectService;
import com.example.springbootrag.service.SearchService;
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

@SpringBootTest
@Testcontainers
class ProjectDeleteIntegrationTest {

    static final int DIM = 768;

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

    /**
     * Fake embeddings: axis 0 = "pressure" topic, axis 2 = constant bias.
     * 768-dim to match the Postgres column.
     */
    @TestConfiguration
    static class FakeEmbeddingConfig {
        @Bean
        @Primary
        EmbeddingProvider fakeEmbeddingProvider() {
            return new EmbeddingProvider() {
                @Override public float[] embed(String text) {
                    String t = text.toLowerCase();
                    float[] v = new float[DIM];
                    v[0] = t.contains("pressure") ? 1f : 0f;
                    v[2] = 0.1f;
                    return v;
                }
                @Override public int dimension() { return DIM; }
            };
        }
    }

    @Autowired ProjectService projectService;
    @Autowired IngestService ingestService;
    @Autowired SearchService searchService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void deletingProjectRemovesChunksFromPostgresAndQdrant() {
        // Create an isolated project with a unique name to avoid collision.
        long projectId = projectService.create("DeleteTest-" + System.nanoTime(), null);

        // Ingest a doc into the project.
        ingestService.ingestMarkdown(projectId, "d", "d.md", "# T\n\npressure content");

        // Verify the chunk is searchable in Qdrant before delete.
        List<SearchHit> before = searchService.search("qdrant", "pressure", 10,
                List.of(projectId), List.of());
        assertThat(before).as("qdrant should return hit before delete").isNotEmpty();

        // Delete the project.
        projectService.delete(projectId);

        // Assert Postgres has no chunks for this project.
        Integer pgCount = jdbc.queryForObject(
                "SELECT count(*) FROM chunks WHERE project_id = ?", Integer.class, projectId);
        assertThat(pgCount).as("postgres chunks should be 0 after project delete").isEqualTo(0);

        // Assert Qdrant no longer returns the doc - unscoped search on "pressure",
        // docId "d" from the deleted project must not appear.
        List<SearchHit> after = searchService.search("qdrant", "pressure", 10,
                List.of(), List.of());
        boolean docStillPresent = after.stream()
                .anyMatch(h -> "d".equals(h.docId()));
        assertThat(docStillPresent)
                .as("docId 'd' from deleted project should not appear in qdrant after delete")
                .isFalse();
    }
}
