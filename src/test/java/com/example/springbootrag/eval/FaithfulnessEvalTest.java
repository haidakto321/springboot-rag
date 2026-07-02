package com.example.springbootrag.eval;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.service.AskService;
import com.example.springbootrag.service.IngestService;
import com.example.springbootrag.web.dto.AskResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.qdrant.QdrantContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Answer faithfulness smoke report using the local LLM as judge.
 * KNOWN LIMITATION: small local judges are noisy - treat results as a smoke
 * signal, not ground truth. RAGAS with a strong judge replaces this later.
 * Run: ./mvnw test "-Dgroups=eval-judge" "-DexcludedGroups="
 */
@SpringBootTest
@Testcontainers
@Tag("eval-judge")
class FaithfulnessEvalTest {

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

    static final String JUDGE_SYSTEM = """
            You are grading a retrieval-augmented answer. Decide whether every factual claim \
            in the answer is supported by the provided context chunks. Reply with exactly one \
            word: yes, partial, or no.""";

    @Autowired IngestService ingestService;
    @Autowired AskService askService;
    @Autowired ChatProvider chat;

    @Test
    void faithfulnessReport() throws Exception {
        ingestCorpus();
        List<GoldenEntry> golden = GoldenSet.load();
        assertThat(golden).isNotEmpty();

        int yes = 0, partial = 0, no = 0, unparsed = 0;
        for (GoldenEntry e : golden) {
            AskResponse resp = askService.ask(e.question());
            StringBuilder ctx = new StringBuilder();
            resp.sources().forEach(s -> ctx.append('[').append(s.index()).append("] ")
                    .append(s.content()).append("\n\n"));
            String verdict = chat.chat(JUDGE_SYSTEM,
                            "Context chunks:\n" + ctx + "\nQuestion: " + e.question()
                                    + "\nAnswer: " + resp.answer())
                    .trim().toLowerCase(Locale.ROOT);
            String head = verdict.split("\\W+")[0];
            switch (head) {
                case "yes" -> yes++;
                case "partial" -> partial++;
                case "no" -> no++;
                default -> unparsed++;
            }
            System.out.printf("[%s] %s%n", head, e.question());
        }
        System.out.printf("%nFaithfulness (local judge, smoke signal only): "
                        + "yes=%d partial=%d no=%d unparsed=%d of %d%n",
                yes, partial, no, unparsed, golden.size());
        assertThat(yes + partial + no + unparsed).isEqualTo(golden.size());
    }

    private void ingestCorpus() throws Exception {
        try (Stream<Path> paths = Files.walk(Path.of("docs"))) {
            for (Path p : paths.filter(p -> p.toString().endsWith(".md")).toList()) {
                String name = p.getFileName().toString();
                String docId = name.substring(0, name.length() - 3).replaceAll("[^a-zA-Z0-9._-]", "-");
                ingestService.ingestMarkdown(docId, name, Files.readString(p));
            }
        }
        ingestService.ingestMarkdown("README", "README.md", Files.readString(Path.of("README.md")));
    }
}
