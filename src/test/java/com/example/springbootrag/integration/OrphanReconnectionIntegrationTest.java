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
 * that shared entity - and ONLY through that path, not because {@code hybrid} already finds it.
 * Hermetic: reuses the deterministic fake ChatProvider + Testcontainers Postgres/Qdrant setup
 * established by SemanticIngestIntegrationTest - no live Ollama.
 *
 * <p>{@code app.graph.candidates=1} narrows {@code graph()}'s internal seed (hybrid at that
 * width) to a single hit so the fixture's content-derived embedding/FTS can actually exclude the
 * orphan from the seed (pgvector's ANN query has no similarity threshold - with more candidates
 * than rows it returns every row regardless of relevance, which would make any seed trivially
 * include both pages and defeat the isolation this test is trying to prove).
 */
@SpringBootTest(properties = {"app.graph.edges=both", "app.graph.candidates=1"})
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

    /**
     * Content-derived fake embedding (hashed bag-of-words, deterministic, offline): each word is
     * hashed into one of 768 buckets and the resulting vector is L2-normalized. Same text always
     * yields the same vector, but DIFFERENT texts get different vectors and share weight only in
     * buckets for words they have in common - unlike a constant vector, this lets vector search
     * genuinely discriminate between the query and the two fixture pages below.
     */
    @TestConfiguration
    static class FakeEmbeddingConfig {
        @Bean
        @Primary
        EmbeddingProvider fakeEmbeddingProvider() {
            return new EmbeddingProvider() {
                @Override public float[] embed(String text) {
                    float[] v = new float[768];
                    for (String word : text.toLowerCase().split("[^a-z0-9]+")) {
                        if (word.isBlank()) continue;
                        int idx = Math.floorMod(word.hashCode(), v.length);
                        v[idx] += 1f;
                    }
                    double norm = 0;
                    for (float f : v) norm += (double) f * f;
                    norm = Math.sqrt(norm);
                    if (norm > 0) {
                        for (int i = 0; i < v.length; i++) v[i] = (float) (v[i] / norm);
                    }
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
        // Well-known page: mentions the shared entity AND the literal query word ("refunds"),
        // so hybrid's FTS + vector search can find it and seed graph()'s expansion.
        ingest.ingestMarkdown(p, "Known-Page", "Known-Page.md",
                "# Known Page\n\nThe PaymentsService handles customer refunds quickly.", Instant.now());
        // Orphan page: NO links to/from it, and its prose deliberately contains NEITHER
        // "PaymentsService" nor "refunds". The only way it can be tied to the shared entity is
        // through the deterministic fake ChatProvider, which tags every ingested chunk with the
        // same "PaymentsService" entity regardless of that chunk's actual text. This removes the
        // orphan's free FTS/vector hit that made the original fixture a tautology.
        ingest.ingestMarkdown(p, "Orphan-Page", "Orphan-Page.md",
                "# Orphan Page\n\nLegacy notes about retry logic and backoff timers.", Instant.now());

        String query = "refunds";

        // Differentiator 1: hybrid alone - at the same candidate width graph()'s seed uses
        // internally (app.graph.candidates=1 above) - must NOT surface the orphan. Its
        // content-derived vector shares no word-bucket with "refunds" and its text has no FTS
        // lexeme match, so both the keyword and vector legs rank Known-Page first and the
        // width-1 cut drops the orphan entirely.
        List<SearchHit> hybrid = search.search("hybrid", query, 1, List.of(p), List.of());
        assertThat(hybrid).extracting(SearchHit::docId).doesNotContain("Orphan-Page");

        // Differentiator 2: graph(), built on that very same narrow seed, DOES surface the
        // orphan - solely because the semantic-entity expansion step (entityRepo ->
        // chunkIdsForEntities) pulls in every chunk linked to the shared "PaymentsService"
        // entity, orphan chunk included. If semantic expansion were broken or disabled (e.g.
        // app.graph.edges=structural), there are no links between these pages, so structural
        // expansion alone would find nothing and this assertion would fail.
        List<SearchHit> graph = search.search("graph", query, 10, List.of(p), List.of());
        assertThat(graph).extracting(SearchHit::docId).contains("Orphan-Page");
    }
}
