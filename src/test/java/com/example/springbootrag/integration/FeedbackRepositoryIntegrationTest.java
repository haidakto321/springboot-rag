package com.example.springbootrag.integration;

import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.model.FeedbackLabel;
import com.example.springbootrag.repository.FeedbackRepository;
import com.example.springbootrag.repository.ProjectRepository;
import com.example.springbootrag.security.TestContexts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class FeedbackRepositoryIntegrationTest {

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

    /** Constant fake embedding - this test never searches, it only needs the context to start. */
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

    @Autowired FeedbackRepository repo;
    @Autowired ProjectRepository projects;
    @Autowired JdbcTemplate jdbc;

    long projectId;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM chunk_feedback");
        jdbc.update("DELETE FROM projects WHERE name <> 'Default'");
        projectId = projects.create("Labels", null);
        // list() joins chunks to hide labels on documents the caller cannot read, so the labelled
        // chunks have to exist. Access label 'public' matches TestContexts.PUBLIC.
        chunk("runbook", 3);
        chunk("runbook", 4);
        chunk("doc", 0);
        chunk("doc", 1);
        chunk("doc", 2);
    }

    /** Minimal readable chunk row - the embedding value is irrelevant here. */
    private void chunk(String docId, int index) {
        String embedding = "[" + "0,".repeat(767) + "0]";
        jdbc.update("INSERT INTO chunks (project_id, doc_id, chunk_index, content, embedding, allowed_groups) "
                + "VALUES (?,?,?,?,?::vector, ARRAY['public'])",
                projectId, docId, index, "body of " + docId + " " + index, embedding);
    }

    @Test
    void repeatVoteOverwritesInsteadOfAppending() {
        repo.upsert(projectId, "how to deploy", "runbook", 3, "up");
        repo.upsert(projectId, "how to deploy", "runbook", 3, "down");

        List<FeedbackLabel> labels = repo.list(TestContexts.PUBLIC, projectId, null, 100);
        assertThat(labels).hasSize(1);
        assertThat(labels.getFirst().rating()).isEqualTo("down");
        assertThat(labels.getFirst().relevant()).isFalse();
    }

    @Test
    void sameChunkUnderADifferentQueryIsASeparateLabel() {
        repo.upsert(projectId, "how to deploy", "runbook", 3, "up");
        repo.upsert(projectId, "rollback steps", "runbook", 3, "down");

        assertThat(repo.list(TestContexts.PUBLIC, projectId, null, 100)).hasSize(2);
        assertThat(repo.list(TestContexts.PUBLIC, projectId, "rollback steps", 100))
                .singleElement()
                .satisfies(l -> assertThat(l.rating()).isEqualTo("down"));
    }

    @Test
    void clearRemovesOnlyTheMatchingLabel() {
        repo.upsert(projectId, "how to deploy", "runbook", 3, "up");
        repo.upsert(projectId, "how to deploy", "runbook", 4, "up");

        assertThat(repo.clear(projectId, "how to deploy", "runbook", 3)).isEqualTo(1);
        assertThat(repo.clear(projectId, "how to deploy", "runbook", 3)).isZero();
        assertThat(repo.list(TestContexts.PUBLIC, projectId, null, 100))
                .singleElement()
                .satisfies(l -> assertThat(l.chunkIndex()).isEqualTo(4));
    }

    @Test
    void listIsNewestFirstAndRespectsLimit() {
        repo.upsert(projectId, "q1", "doc", 0, "up");
        repo.upsert(projectId, "q2", "doc", 1, "up");
        repo.upsert(projectId, "q3", "doc", 2, "down");

        List<FeedbackLabel> page = repo.list(TestContexts.PUBLIC, projectId, null, 2);
        assertThat(page).hasSize(2);
        assertThat(page.stream().map(FeedbackLabel::query)).containsExactly("q3", "q2");
        assertThat(repo.count(projectId)).isEqualTo(3);
    }

    @Test
    void labelsOfOtherProjectsAreNotReturned() {
        long other = projects.create("Other", null);
        String embedding = "[" + "0,".repeat(767) + "0]";
        jdbc.update("INSERT INTO chunks (project_id, doc_id, chunk_index, content, embedding, allowed_groups) "
                + "VALUES (?,?,?,?,?::vector, ARRAY['public'])", other, "doc", 0, "other body", embedding);
        repo.upsert(projectId, "q", "doc", 0, "up");
        repo.upsert(other, "q", "doc", 0, "down");

        assertThat(repo.list(TestContexts.PUBLIC, projectId, null, 100))
                .singleElement()
                .satisfies(l -> assertThat(l.rating()).isEqualTo("up"));
    }

    @Test
    void deletingAProjectCascadesItsLabels() {
        repo.upsert(projectId, "q", "doc", 0, "up");
        projects.delete(projectId);

        assertThat(repo.count(null)).isZero();
    }

    @Test
    void aLabelOnARestrictedChunkIsHiddenFromOtherGroups() {
        String embedding = "[" + "0,".repeat(767) + "0]";
        jdbc.update("INSERT INTO chunks (project_id, doc_id, chunk_index, content, embedding, allowed_groups) "
                + "VALUES (?,?,?,?,?::vector, ARRAY['hr'])", projectId, "secret", 0, "salary", embedding);
        repo.upsert(projectId, "pay", "secret", 0, "up");

        assertThat(repo.list(TestContexts.PUBLIC, projectId, null, 100))
                .as("a label carries a doc id and a query - both leak if the chunk is unreadable")
                .noneMatch(l -> l.docId().equals("secret"));
        assertThat(repo.list(TestContexts.of("hr"), projectId, null, 100))
                .anyMatch(l -> l.docId().equals("secret"));
    }

    @Test
    void databaseRejectsAnUnknownRating() {
        assertThatThrownBy(() -> repo.upsert(projectId, "q", "doc", 0, "maybe"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
