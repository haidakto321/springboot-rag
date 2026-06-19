package com.example.springbootrag.integration;

import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.service.SearchService;
import com.example.springbootrag.service.IngestService;
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
        // dimension stays at the default 768 so it matches schema.sql's vector(768) column.
    }

    /**
     * Deterministic fake embeddings so the test does not need Ollama.
     * 768-dim to match the Postgres column. Axis 0 = "pressure" topic,
     * axis 1 = "invoice" topic, axis 2 = constant bias (keeps vectors non-zero).
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
                    v[0] = (t.contains("pressure") || t.contains("seepage") || t.contains("leak")) ? 1f : 0f;
                    v[1] = (t.contains("invoice") || t.contains("payment")) ? 1f : 0f;
                    v[2] = 0.1f;
                    return v;
                }
                @Override public int dimension() { return DIM; }
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

        // compare returns all backends with timing (rerank added via the default IdentityReranker)
        var cmp = searchService.compare("pressure", 5);
        assertThat(cmp.keySet()).containsExactly("fts", "pgvector", "qdrant", "hybrid", "rerank");
        assertThat(cmp.get("fts").elapsedMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void rerankReturnsResultsViaIdentityByDefault() {
        ingestService.ingest("doc1", "hydraulic seepage caused a pressure drop on line 3");
        ingestService.ingest("doc2", "the invoice payment was overdue by thirty days");

        // No app.rerank.provider configured -> IdentityReranker -> equals hybrid trimmed to topK.
        List<SearchHit> out = searchService.search("rerank", "pressure", 5);
        assertThat(out).isNotEmpty();
        assertThat(out.size()).isLessThanOrEqualTo(5);
    }
}
