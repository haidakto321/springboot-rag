package com.example.springbootrag.integration;

import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.repository.ProjectRepository;
import com.example.springbootrag.service.IngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.qdrant.QdrantContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// edges=structural: no ChatProvider stub here, so pin the mode to avoid a real-Ollama
// call from the app-wide "both" default.
@SpringBootTest(properties = "app.graph.edges=structural")
@AutoConfigureMockMvc
@Testcontainers
@WithMockUser(username = "alice", authorities = {"GROUP_public"})   // real filter chain: an HTTP test needs an identity, and one with a group to read anything
class DocumentIntegrationTest {

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

    /** Constant fake embedding: this test exercises upload plumbing, not similarity. */
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

    @Autowired MockMvc mvc;
    @Autowired IngestService ingestService;
    @Autowired ProjectRepository projectRepository;

    @BeforeEach
    void cleanup() {
        ingestService.delete("My-Notes");
        ingestService.delete("doc");
    }

    @Test
    void uploadListDeleteRoundTrip() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "My Notes.md", "text/markdown",
                "# Notes\n\nsome useful content here".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/documents").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.docId").value("My-Notes"))
                .andExpect(jsonPath("$.chunksStored").value(1));

        mvc.perform(get("/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].docId").value("My-Notes"))
                .andExpect(jsonPath("$[0].sourceFile").value("My Notes.md"))
                .andExpect(jsonPath("$[0].chunkCount").value(1));

        mvc.perform(delete("/documents/My-Notes"))
                .andExpect(status().isOk());

        mvc.perform(get("/documents"))
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void listChunksReturnsOrderedChunks() throws Exception {
        // Two headings -> two chunks, each carrying its heading path.
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.md", "text/markdown",
                "# A\n\nfirst body\n\n## B\n\nsecond body".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/documents").file(file)).andExpect(status().isOk());

        mvc.perform(get("/documents/doc/chunks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].index").value(0))
                .andExpect(jsonPath("$[0].headingPath").value("# A"))
                .andExpect(jsonPath("$[0].content").value(org.hamcrest.Matchers.containsString("first body")))
                .andExpect(jsonPath("$[1].index").value(1))
                .andExpect(jsonPath("$[1].content").value(org.hamcrest.Matchers.containsString("second body")));
    }

    @Test
    void listChunksUnknownDocReturnsEmpty() throws Exception {
        mvc.perform(get("/documents/nope/chunks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void rejectsNonMarkdownFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "data.pdf", "application/pdf", new byte[]{1, 2, 3});

        mvc.perform(multipart("/documents").file(file))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsInvalidUtf8() throws Exception {
        // 0xC3 followed by 0x28 is malformed UTF-8
        MockMultipartFile file = new MockMultipartFile(
                "file", "bad.md", "text/markdown", new byte[]{(byte) 0xC3, 0x28});

        mvc.perform(multipart("/documents").file(file))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reuploadReplacesInsteadOfDuplicating() throws Exception {
        MockMultipartFile v1 = new MockMultipartFile(
                "file", "doc.md", "text/markdown",
                "# A\n\nfirst version".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile v2 = new MockMultipartFile(
                "file", "doc.md", "text/markdown",
                "# A\n\nsecond version".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/documents").file(v1)).andExpect(status().isOk());
        mvc.perform(multipart("/documents").file(v2)).andExpect(status().isOk());

        mvc.perform(get("/documents"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].chunkCount").value(1));
    }

    @Test
    void projectScopedUploadIsIsolatedFromOtherProjects() throws Exception {
        long projectA = projectRepository.create("IsolationTestA", null);
        long projectB = projectRepository.create("IsolationTestB", null);
        try {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "note.md", "text/markdown",
                    "# Note\n\nsome isolated content".getBytes(StandardCharsets.UTF_8));

            // Upload to project A via project-scoped route
            mvc.perform(multipart("/projects/" + projectA + "/documents").file(file))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.docId").value("note"))
                    .andExpect(jsonPath("$.chunksStored").value(1));

            // GET for project A shows the document with the right chunk count
            mvc.perform(get("/projects/" + projectA + "/documents"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].docId").value("note"))
                    .andExpect(jsonPath("$[0].chunkCount").value(1));

            // GET for project B returns nothing - isolation is enforced
            mvc.perform(get("/projects/" + projectB + "/documents"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty());
        } finally {
            ingestService.delete(projectA, "note");
            projectRepository.delete(projectA);
            projectRepository.delete(projectB);
        }
    }
}
