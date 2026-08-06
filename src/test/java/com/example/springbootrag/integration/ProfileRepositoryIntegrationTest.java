package com.example.springbootrag.integration;

import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.repository.ProfileRepository;
import com.example.springbootrag.service.ProjectService;
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

import static org.assertj.core.api.Assertions.assertThat;

/** render_profile CRUD: upsert bumps a version, a missing profile is empty rather than an error. */
@SpringBootTest(properties = "app.graph.edges=structural")
@Testcontainers
class ProfileRepositoryIntegrationTest {

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
                @Override public float[] embed(String text) {
                    float[] v = new float[768];
                    v[0] = 1f;
                    return v;
                }
                @Override public int dimension() { return 768; }
            };
        }
    }

    @Autowired ProfileRepository profiles;
    @Autowired ProjectService projectService;

    @Test
    void upsertBumpsVersionAndKeepsBody() {
        long projectId = projectService.defaultProjectId();

        int v1 = profiles.upsert(projectId, "invoice", """
                {"exclude":["rawOcrText"]}""");
        assertThat(v1).isEqualTo(1);

        int v2 = profiles.upsert(projectId, "invoice", """
                {"exclude":["rawOcrText","internal.*"]}""");
        assertThat(v2).isEqualTo(2);

        var found = profiles.find(projectId, "invoice").orElseThrow();
        assertThat(found.version()).isEqualTo(2);
        assertThat(found.body()).contains("internal.*");
    }

    @Test
    void missingProfileIsEmptyNotAnError() {
        assertThat(profiles.find(projectService.defaultProjectId(), "never-seen")).isEmpty();
    }

    @Test
    void listReturnsOneRowPerDocType() {
        long projectId = projectService.defaultProjectId();
        profiles.upsert(projectId, "contract", "{}");
        profiles.upsert(projectId, "delivery-note", "{}");

        assertThat(profiles.list(projectId))
                .extracting(ProfileRepository.StoredProfile::docType)
                .contains("contract", "delivery-note");
    }
}
