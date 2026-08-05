package com.example.springbootrag.integration;

import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.repository.DocEdgeRepository;
import com.example.springbootrag.service.IngestService;
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

import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

// edges=structural: this test exercises doc-link edges only; pin the mode so it stays
// hermetic and unaffected by the app-wide "both" default (no ChatProvider stub here).
@SpringBootTest(properties = "app.graph.edges=structural")
@Testcontainers
class GraphIngestIntegrationTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Container
    static QdrantContainer qdrant =
            new QdrantContainer(DockerImageName.parse("qdrant/qdrant:v1.9.0"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
        r.add("app.qdrant.host", qdrant::getHost);
        r.add("app.qdrant.port", qdrant::getGrpcPort);
    }

    /** Constant fake embedding: this test exercises graph edge/cascade plumbing, not similarity. */
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
