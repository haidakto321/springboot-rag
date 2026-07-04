package com.example.springbootrag.integration;

import com.example.springbootrag.chat.ChatProvider;
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

/**
 * Proves the spec's headline claim: a page with NO inbound/outbound links (an orphan) but
 * sharing an entity with a well-known page IS retrieved by the {@code graph} backend through
 * that shared entity. Hermetic: reuses the deterministic fake ChatProvider + fake embedding +
 * Testcontainers Postgres/Qdrant setup established by SemanticIngestIntegrationTest - no live
 * Ollama.
 */
@SpringBootTest(properties = "app.graph.edges=both")
@Testcontainers
class OrphanReconnectionIntegrationTest {

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

    /** Constant fake embedding: this test exercises graph expansion plumbing, not similarity. */
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

    /** Deterministic fake ChatProvider: no real Ollama, always returns the same fixed entity JSON
     *  so both pages link to the same "PaymentsService" entity regardless of their text. */
    @TestConfiguration
    static class FakeChatConfig {
        @Bean
        @Primary
        ChatProvider fakeChatProvider() {
            return new ChatProvider() {
                @Override public String chat(String systemPrompt, String userPrompt) {
                    return """
                            {"entities":[{"name":"PaymentsService","type":"service"}],
                             "relations":[]}
                            """;
                }
            };
        }
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

        List<SearchHit> graph = search.search("graph", "PaymentsService", 10, List.of(p), List.of());
        assertThat(graph).extracting(SearchHit::docId).contains("Orphan-Page");
    }
}
