package com.example.springbootrag.integration;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.repository.EntityRepository;
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

@SpringBootTest(properties = "app.graph.edges=both")
@Testcontainers
class SemanticIngestIntegrationTest {

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

    /** Constant fake embedding: this test exercises entity extraction plumbing, not similarity. */
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

    /** Deterministic fake ChatProvider: no real Ollama, always returns the same fixed entity JSON. */
    @TestConfiguration
    static class FakeChatConfig {
        @Bean
        @Primary
        ChatProvider fakeChatProvider() {
            return new ChatProvider() {
                @Override public String chat(String systemPrompt, String userPrompt) {
                    return """
                            {"entities":[{"name":"PaymentsService","type":"service"},{"name":"Alice","type":"team"}],
                             "relations":[{"src":"Alice","rel":"owns","dst":"PaymentsService"}]}
                            """;
                }
            };
        }
    }

    @Autowired IngestService ingest;
    @Autowired EntityRepository entities;
    @Autowired JdbcTemplate jdbc;

    private long projectId() {
        return jdbc.queryForObject("SELECT id FROM projects ORDER BY id LIMIT 1", Long.class);
    }

    @Test
    void ingestExtractsEntitiesAndDeleteGcsThem() {
        long p = projectId();
        ingest.ingestMarkdown(p, "Feature-X", "Feature-X.md",
                "# Feature X\n\nAlice owns the PaymentsService.", Instant.now());

        // A deterministic fake ChatProvider (see FakeChatConfig above) yields at least one entity.
        assertThat(entities.matchEntityIds(p, List.of("PaymentsService"), 1)).isNotEmpty();

        ingest.delete(p, "Feature-X");
        assertThat(entities.matchEntityIds(p, List.of("PaymentsService"), 1)).isEmpty();
    }
}
