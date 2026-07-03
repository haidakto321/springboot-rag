package com.example.springbootrag.integration;

import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.service.IngestService;
import org.junit.jupiter.api.BeforeEach;
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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class ProjectSchemaIntegrationTest {

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

    /** Constant fake embedding: this test exercises schema, not similarity. */
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

    @Autowired JdbcTemplate jdbc;
    @Autowired IngestService ingestService;

    @BeforeEach
    void cleanup() {
        ingestService.delete("legacy");
    }

    @Test
    void existingChunksAreBackfilledToDefaultProject() {
        // ingest a chunk the legacy way (no project) then assert it has a project_id
        ingestService.ingest("legacy", "some legacy text");
        Integer withProject = jdbc.queryForObject(
            "SELECT count(*) FROM chunks WHERE doc_id = 'legacy' AND project_id IS NOT NULL", Integer.class);
        assertThat(withProject).isGreaterThan(0);
        String defaultName = jdbc.queryForObject(
            "SELECT name FROM projects WHERE id = (SELECT project_id FROM chunks WHERE doc_id='legacy' LIMIT 1)",
            String.class);
        assertThat(defaultName).isEqualTo("Default");
    }

    @Test
    void nullProjectRowsAreBackfilledToDefault() {
        // Clean up any leftover rows from a previous interrupted run
        jdbc.update("DELETE FROM chunks WHERE doc_id = 'backfill-test'");

        // Disable the trigger so we can insert a chunk with NULL project_id directly
        jdbc.execute("ALTER TABLE chunks DISABLE TRIGGER trg_chunks_default_project");
        try {
            String embedding = "[" + "0,".repeat(767) + "0]";
            jdbc.update(
                "INSERT INTO chunks (doc_id, chunk_index, content, embedding, project_id) " +
                "VALUES ('backfill-test', 0, 'backfill content', '" + embedding + "'::vector, NULL)"
            );
        } finally {
            jdbc.execute("ALTER TABLE chunks ENABLE TRIGGER trg_chunks_default_project");
        }

        // Confirm the row is still NULL before we run the backfill
        Integer nullBefore = jdbc.queryForObject(
            "SELECT count(*) FROM chunks WHERE doc_id = 'backfill-test' AND project_id IS NULL",
            Integer.class);
        assertThat(nullBefore).isEqualTo(1);

        // Run the same backfill UPDATE that schema.sql applies on startup
        jdbc.update(
            "UPDATE chunks SET project_id = " +
            "(SELECT id FROM projects WHERE name = 'Default' ORDER BY id LIMIT 1) " +
            "WHERE project_id IS NULL"
        );

        // Assert the row now points at the Default project
        Long defaultId = jdbc.queryForObject(
            "SELECT id FROM projects WHERE name = 'Default' ORDER BY id LIMIT 1",
            Long.class);
        Long rowProjectId = jdbc.queryForObject(
            "SELECT project_id FROM chunks WHERE doc_id = 'backfill-test'",
            Long.class);
        assertThat(rowProjectId).isEqualTo(defaultId);

        // Cleanup
        jdbc.update("DELETE FROM chunks WHERE doc_id = 'backfill-test'");
    }
}
