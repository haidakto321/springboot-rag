package com.example.springbootrag.integration;

import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.trace.RagTrace;
import com.example.springbootrag.trace.TraceRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class TraceRepositoryIntegrationTest {

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
                @Override public float[] embed(String text) { return new float[768]; }
                @Override public int dimension() { return 768; }
            };
        }
    }

    @Autowired TraceRepository repo;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM rag_trace");
    }

    private static RagTrace trace(String principal, String rawQuery, String condensed) {
        return new RagTrace(
                UUID.randomUUID(), Instant.now(), principal, List.of(5L, 7L),
                rawQuery, condensed, "rerank",
                List.of(new RagTrace.Retrieved("runbook", 3, 0.87),
                        new RagTrace.Retrieved("policy", 0, 0.42)),
                Map.of("embed", 12L, "retrieve", 88L, "generate", 2400L, "total", 2500L),
                310, 145, "the answer [1]", "cited");
    }

    @Test
    void roundTripsEveryFieldIncludingTheJsonbParts() {
        RagTrace written = trace("alice", "what about overlap?", "how does chunk overlap work?");
        repo.insert(written);

        RagTrace read = repo.recent("alice", 10).getFirst();

        assertThat(read.requestId()).isEqualTo(written.requestId());
        assertThat(read.projectIds()).containsExactly(5L, 7L);
        assertThat(read.rawQuery()).isEqualTo("what about overlap?");
        assertThat(read.condensedQuery()).isEqualTo("how does chunk overlap work?");
        assertThat(read.retrieved()).hasSize(2);
        assertThat(read.retrieved().getFirst().docId()).isEqualTo("runbook");
        assertThat(read.retrieved().getFirst().score()).isEqualTo(0.87);
        assertThat(read.stageLatencyMs()).containsEntry("generate", 2400L).containsEntry("total", 2500L);
        assertThat(read.promptTokens()).isEqualTo(310);
        assertThat(read.completionTokens()).isEqualTo(145);
        assertThat(read.guardReason()).isEqualTo("cited");
    }

    @Test
    void tracesOfOtherPrincipalsAreNotReturned() {
        // A trace holds a question and the documents it matched - the same leak as a title.
        repo.insert(trace("alice", "hr question", null));
        repo.insert(trace("haiks", "eng question", null));

        assertThat(repo.recent("haiks", 10))
                .singleElement()
                .satisfies(t -> assertThat(t.rawQuery()).isEqualTo("eng question"));
    }

    @Test
    void insertingTheSameRequestIdTwiceIsIgnored() {
        RagTrace t = trace("alice", "q", null);
        repo.insert(t);
        repo.insert(t);

        assertThat(repo.recent("alice", 10)).hasSize(1);
    }

    @Test
    void pruneKeepsTheNewestRowsForThatPrincipalOnly() {
        for (int i = 0; i < 5; i++) {
            repo.insert(trace("alice", "q" + i, null));
        }
        repo.insert(trace("haiks", "keep me", null));

        repo.prune("alice", 2);

        assertThat(repo.recent("alice", 10)).hasSize(2);
        assertThat(repo.recent("haiks", 10)).hasSize(1);
    }

    @Test
    void nullTokenCountsSurviveAsNullNotZero() {
        // "not reported" and "free" are different facts; recording 0 would invent the second.
        RagTrace t = new RagTrace(UUID.randomUUID(), Instant.now(), "alice", List.of(),
                "q", null, "fts", List.of(), Map.of("total", 4L), null, null, null, "no-hits");
        repo.insert(t);

        RagTrace read = repo.recent("alice", 1).getFirst();
        assertThat(read.promptTokens()).isNull();
        assertThat(read.completionTokens()).isNull();
        assertThat(read.retrieved()).isEmpty();
        assertThat(read.projectIds()).isEmpty();
    }
}
