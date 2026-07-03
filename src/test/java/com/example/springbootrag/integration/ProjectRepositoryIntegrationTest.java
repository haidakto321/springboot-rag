package com.example.springbootrag.integration;

import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.repository.ProjectRepository;
import com.example.springbootrag.web.dto.ProjectSummary;
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
class ProjectRepositoryIntegrationTest {

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

    /** Constant fake embedding - exercises repository plumbing, not similarity. */
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

    @Autowired ProjectRepository repo;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM chunks WHERE doc_id = 'd'");
        // Remove test projects created in previous tests; preserve the seeded Default project.
        // Chunks belonging to deleted projects cascade via FK.
        jdbc.update("DELETE FROM projects WHERE name <> 'Default'");
    }

    @Test
    void createListRenameGroupDelete() {
        long fe = repo.create("Frontend", "MyApp");
        long be = repo.create("Backend", "MyApp");
        long solo = repo.create("Scratch", null);

        assertThat(repo.listGroups()).containsExactly("MyApp");
        assertThat(repo.idsInGroup("MyApp")).containsExactlyInAnyOrder(fe, be);

        repo.rename(fe, "Web");
        assertThat(repo.find(fe).orElseThrow().name()).isEqualTo("Web");

        repo.setGroup(solo, "MyApp");
        assertThat(repo.idsInGroup("MyApp")).contains(solo);

        // Verify that setGroup(null) actually clears the group.
        repo.setGroup(fe, null);
        assertThat(repo.find(fe).orElseThrow().groupName()).isNull();

        repo.delete(be);
        assertThat(repo.find(be)).isEmpty();
    }

    @Test
    void listWithCountsReportsDocAndChunkTotals() {
        // "A" in group "Alpha" must sort before "P" with no group (ORDER BY group_name NULLS LAST, name).
        long a = repo.create("A", "Alpha");
        long p = repo.create("P", null);
        String embedding = "[" + "0,".repeat(767) + "0]";
        jdbc.update(
            "INSERT INTO chunks (project_id, doc_id, chunk_index, content, embedding) VALUES (?,?,?,?, ?::vector)",
            p, "d", 0, "some text body", embedding);
        jdbc.update(
            "INSERT INTO chunks (project_id, doc_id, chunk_index, content, embedding) VALUES (?,?,?,?, ?::vector)",
            p, "d", 1, "more text body", embedding);

        var list = repo.listWithCounts();

        // Assert ordering: grouped project "A" (Alpha) comes before ungrouped project "P" (NULLS LAST).
        var names = list.stream().map(ProjectSummary::name).toList();
        assertThat(names.indexOf("A")).isLessThan(names.indexOf("P"));

        ProjectSummary s = list.stream()
            .filter(x -> x.id() == p).findFirst().orElseThrow();
        assertThat(s.docCount()).isEqualTo(1);
        assertThat(s.chunkCount()).isEqualTo(2);
    }
}
