package com.example.springbootrag.integration;

import com.example.springbootrag.chunk.HeadingStyle;
import com.example.springbootrag.config.ChunkProperties;
import com.example.springbootrag.embedding.EmbeddingProvider;
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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The containment property: the breadcrumb reaches the embedder in every style, but reaches
 * STORAGE only when the style is not EMBED_ONLY. Stored text feeds tsv, the reranker, the answer
 * prompt and the UI, so a leak here would silently widen the experiment's blast radius.
 */
// edges=structural: no ChatProvider stub here, so pin the mode to avoid a real-Ollama call.
@SpringBootTest(properties = "app.graph.edges=structural")
@Testcontainers
class HeadingStyleStorageIntegrationTest {

    private static final String MD = """
            # Guide

            ## Setup

            Pass the verbose flag.
            """;
    private static final String BREADCRUMB = "# Guide > ## Setup";

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

    /** Records what was handed to the embedder so the test can assert on it. */
    static final List<String> EMBEDDED = new ArrayList<>();

    @TestConfiguration
    static class RecordingEmbeddingConfig {
        @Bean
        @Primary
        EmbeddingProvider recordingEmbeddingProvider() {
            return new EmbeddingProvider() {
                @Override public float[] embed(String text) {
                    EMBEDDED.add(text);
                    float[] v = new float[768];
                    v[0] = 1f;
                    return v;
                }
                @Override public int dimension() { return 768; }
            };
        }
    }

    @Autowired IngestService ingestService;
    @Autowired ChunkProperties chunkProps;
    @Autowired JdbcTemplate jdbc;

    private String storedContentOf(String docId) {
        return jdbc.queryForObject(
                "select content from chunks where doc_id = ? order by chunk_index limit 1",
                String.class, docId);
    }

    @Test
    void fullStyleKeepsTheBreadcrumbInBothEmbeddedAndStoredText() {
        chunkProps.setHeadingStyle(HeadingStyle.FULL);
        EMBEDDED.clear();

        ingestService.ingestMarkdown("full-doc", "guide.md", MD);

        assertThat(EMBEDDED).isNotEmpty();
        assertThat(EMBEDDED.get(0)).startsWith(BREADCRUMB);
        assertThat(storedContentOf("full-doc")).startsWith(BREADCRUMB);
    }

    @Test
    void embedOnlyStyleKeepsTheBreadcrumbOutOfStorage() {
        chunkProps.setHeadingStyle(HeadingStyle.EMBED_ONLY);
        EMBEDDED.clear();

        ingestService.ingestMarkdown("embed-only-doc", "guide.md", MD);

        assertThat(EMBEDDED).isNotEmpty();
        assertThat(EMBEDDED.get(0)).startsWith(BREADCRUMB);          // reached the embedder
        String stored = storedContentOf("embed-only-doc");
        assertThat(stored).doesNotContain(BREADCRUMB)                 // did NOT reach storage
                .isEqualTo("Pass the verbose flag.");
    }

    @Test
    void noneStyleKeepsTheBreadcrumbOutOfBoth() {
        chunkProps.setHeadingStyle(HeadingStyle.NONE);
        EMBEDDED.clear();

        ingestService.ingestMarkdown("none-doc", "guide.md", MD);

        assertThat(EMBEDDED).isNotEmpty();
        assertThat(EMBEDDED.get(0)).doesNotContain(BREADCRUMB);
        assertThat(storedContentOf("none-doc")).doesNotContain(BREADCRUMB);
    }
}
