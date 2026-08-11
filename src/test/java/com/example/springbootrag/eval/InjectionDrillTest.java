package com.example.springbootrag.eval;

import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.guard.SecretScanner;
import com.example.springbootrag.repository.ProjectRepository;
import com.example.springbootrag.repository.QuarantineRepository;
import com.example.springbootrag.security.CurrentUser;
import com.example.springbootrag.security.SearchContext;
import com.example.springbootrag.service.SearchService;
import com.example.springbootrag.web.DocumentController;
import com.example.springbootrag.web.QuarantineController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.qdrant.QdrantContainer;
import org.testcontainers.utility.DockerImageName;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replays the 2026-08-05 injection drill against the current code.
 *
 * <p>Real Postgres and Qdrant, fake embeddings, no live model: every assertion here is about what
 * is INDEXED and what is RETRIEVABLE, not about what a model chooses to say. Tagging it
 * {@code eval-injection} keeps it out of the normal build because it needs containers, not because
 * it is slow or flaky.
 */
@Tag("eval-injection")
@SpringBootTest
@Testcontainers
class InjectionDrillTest {

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
        @Bean @Primary EmbeddingProvider fakeEmbeddingProvider() {
            return new EmbeddingProvider() {
                @Override public float[] embed(String text) {
                    float[] v = new float[768];
                    Arrays.fill(v, 0.1f);
                    return v;
                }
                @Override public int dimension() { return 768; }
            };
        }
    }

    @Autowired DocumentController documents;
    @Autowired QuarantineController quarantine;
    @Autowired QuarantineRepository pen;
    @Autowired SearchService search;
    @Autowired ProjectRepository projects;

    long projectId;
    final SearchContext alice = SearchContext.of("alice", Set.of("public"));

    @SuppressWarnings("unchecked")
    static Map<String, Object> drill() {
        try (InputStream in = InjectionDrillTest.class.getResourceAsStream("/eval/injection-drill.yaml")) {
            if (in == null) {
                throw new IllegalStateException("/eval/injection-drill.yaml not on the test classpath");
            }
            return (Map<String, Object>) new Yaml().load(in);
        } catch (Exception e) {
            throw new IllegalStateException("could not load the injection drill", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> document() {
        return (Map<String, Object>) drill().get("document");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> probes() {
        return (List<Map<String, Object>>) drill().get("probes");
    }

    @BeforeEach
    void setUp() {
        projectId = projects.create("injection-drill-" + System.nanoTime(), null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a",
                        List.of(new SimpleGrantedAuthority(CurrentUser.GROUP_PREFIX + "public"))));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private com.example.springbootrag.web.dto.IngestResponse uploadDrill() {
        Map<String, Object> doc = document();
        MockMultipartFile file = new MockMultipartFile("file",
                (String) doc.get("sourceFile"), "text/markdown",
                ((String) doc.get("text")).getBytes(StandardCharsets.UTF_8));
        return documents.uploadToProject(projectId, file, List.of("public"));
    }

    private static String docId() {
        return (String) document().get("docId");
    }

    @SuppressWarnings("unchecked")
    private static List<String> expectedFindings() {
        return (List<String>) drill().get("expectedFindings");
    }

    @Test
    void theDrillPageIsHeldAndEveryProbeFindsNothing() {
        var res = uploadDrill();

        assertThat(res.quarantined()).isTrue();
        assertThat(res.chunksStored()).isZero();
        assertThat(res.findings()).extracting(SecretScanner.Finding::rule)
                .containsAll(expectedFindings());
        // The response and the pen listing are both places the secret could escape to.
        assertThat(res.toString()).doesNotContain("hunter2");

        for (Map<String, Object> probe : probes()) {
            String question = (String) probe.get("question");
            int expected = (int) probe.get("expectHits");
            assertThat(search.search(alice, "hybrid", question, 10, List.of(projectId), List.of()))
                    .as("probe: %s", question)
                    .hasSize(expected);
        }
    }

    @Test
    void releasingItRestoresBothTheAnswerAndTheLeak() {
        // Deliberate, and the point of the whole drill. The control is QUARANTINE, not the model:
        // once a human releases the page, "hunter2" is retrievable text in a document the caller is
        // allowed to read, exactly as RAG-MASTERY section 5 records. A test that expected a refusal
        // here would be measuring a control this system does not have.
        uploadDrill();

        quarantine.release(projectId, docId());

        var leak = search.search(alice, "hybrid", "what is the recovery code", 10,
                List.of(projectId), List.of());
        assertThat(leak).isNotEmpty();
        assertThat(leak).anyMatch(h -> h.content().contains("hunter2"));

        var fact = search.search(alice, "hybrid", "what is the meal allowance per day", 10,
                List.of(projectId), List.of());
        assertThat(fact).anyMatch(h -> h.content().contains("40 EUR"));

        assertThat(pen.find(alice, projectId, docId())).isEmpty();
    }
}
