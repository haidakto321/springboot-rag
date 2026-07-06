package com.example.springbootrag.integration;

import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.repository.PgVectorRepository;
import com.example.springbootrag.repository.ProjectRepository;
import com.example.springbootrag.tool.WikiImporter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.qdrant.QdrantContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// edges=structural: import exercises link/hierarchy edges only; pin the mode so no ChatProvider/Ollama
// call happens from the app-wide default.
@SpringBootTest(properties = "app.graph.edges=structural")
@AutoConfigureMockMvc
@Testcontainers
class WikiImportControllerIntegrationTest {

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

    /** Content-derived fake embedding: exercises import plumbing, not similarity. */
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
    @Autowired ObjectMapper mapper;
    @Autowired WikiImporter wikiImporter;
    @Autowired PgVectorRepository pgVector;
    @Autowired ProjectRepository projectRepository;

    /**
     * Builds a non-git temp dir with 3 real .md pages (one linking another) plus an empty page
     * and a whitespace-only page - both must be skipped by the importer. Importer falls back to mtime.
     */
    private Path buildWiki() throws Exception {
        Path dir = Files.createTempDirectory("wiki-import-test");
        Files.writeString(dir.resolve("A.md"), "# Page A\n\nLinks to [B](/B).", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("B.md"), "# Page B\n\nSome content.", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("C.md"), "# Page C\n\nMore content.", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("Empty.md"), "", StandardCharsets.UTF_8);          // skipped
        Files.writeString(dir.resolve("Blank.md"), "   \n\t\n", StandardCharsets.UTF_8); // skipped
        return dir;
    }

    @Test
    void streamsProgressAndImportsAllPages() throws Exception {
        long projectId = projectRepository.create("WikiImportStream", null);
        Path wiki = buildWiki();
        try {
            String body = mapper.writeValueAsString(
                    java.util.Map.of("path", wiki.toString()));

            MvcResult async = mvc.perform(post("/projects/" + projectId + "/import-wiki")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(request().asyncStarted())
                    .andReturn();

            String ndjson = mvc.perform(asyncDispatch(async))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            List<JsonNode> frames = parseFrames(ndjson);
            long progress = frames.stream().filter(f -> "progress".equals(f.get("type").asText())).count();
            assertThat(progress).isEqualTo(3);
            JsonNode start = frames.stream().filter(f -> "start".equals(f.get("type").asText()))
                    .findFirst().orElseThrow();
            assertThat(start.get("total").asInt()).isEqualTo(3);
            JsonNode done = frames.get(frames.size() - 1);
            assertThat(done.get("type").asText()).isEqualTo("done");
            assertThat(done.get("pagesImported").asInt()).isEqualTo(3);
            assertThat(done.get("pagesFailed").asInt()).isEqualTo(0);

            // Docs actually landed.
            assertThat(pgVector.listDocuments(projectId)).hasSize(3);
        } finally {
            cleanup(projectId, wiki);
        }
    }

    @Test
    void directCallbackReportsTotalAndIncrements() throws Exception {
        long projectId = projectRepository.create("WikiImportCallback", null);
        Path wiki = buildWiki();
        try {
            List<Integer> dones = new ArrayList<>();
            int[] seenTotal = {-1};
            int count = wikiImporter.importDir(projectId, wiki, (done, total, doc) -> {
                seenTotal[0] = total;
                dones.add(done);
            });
            assertThat(count).isEqualTo(3);
            assertThat(seenTotal[0]).isEqualTo(3);
            assertThat(dones).containsExactly(1, 2, 3);
        } finally {
            cleanup(projectId, wiki);
        }
    }

    @Test
    void nonExistentPathReturns400() throws Exception {
        long projectId = projectRepository.create("WikiImportBadPath", null);
        try {
            String body = mapper.writeValueAsString(
                    java.util.Map.of("path", "Z:/no/such/wiki/dir-does-not-exist"));
            mvc.perform(post("/projects/" + projectId + "/import-wiki")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        } finally {
            projectRepository.delete(projectId);
        }
    }

    private List<JsonNode> parseFrames(String ndjson) throws Exception {
        List<JsonNode> frames = new ArrayList<>();
        for (String line : ndjson.split("\n")) {
            if (!line.isBlank()) frames.add(mapper.readTree(line));
        }
        return frames;
    }

    private void cleanup(long projectId, Path wiki) throws Exception {
        projectRepository.delete(projectId);
        try (var paths = Files.walk(wiki)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        }
    }
}
