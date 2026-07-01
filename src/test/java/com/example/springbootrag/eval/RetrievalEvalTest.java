package com.example.springbootrag.eval;

import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.service.IngestService;
import com.example.springbootrag.service.SearchService;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retrieval quality report. NOT part of the normal build.
 * Prereqs: Docker running, Ollama at localhost:11434 with nomic-embed-text pulled.
 * Run: ./mvnw test "-Dgroups=eval" "-DexcludedGroups="
 */
@SpringBootTest
@Testcontainers
@Tag("eval")
class RetrievalEvalTest {

    static final List<String> BACKENDS = List.of("fts", "pgvector", "qdrant", "hybrid", "rerank");
    static final int TOP_K = 10;

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
        // NO fake embedding config: eval uses the real Ollama provider.
    }

    @Autowired IngestService ingestService;
    @Autowired SearchService searchService;

    @Test
    void retrievalMetricsReport() throws Exception {
        int docs = ingestCorpus();
        List<GoldenEntry> golden = GoldenSet.load();
        assertThat(docs).isGreaterThan(0);
        assertThat(golden).isNotEmpty();

        System.out.printf("%nRetrieval eval: %d docs, %d questions, topK=%d%n", docs, golden.size(), TOP_K);
        System.out.printf("%-10s %10s %10s %10s%n", "backend", "recall@5", "MRR", "hit@1");
        for (String backend : BACKENDS) {
            double recall5 = 0, mrr = 0, hit1 = 0;
            for (GoldenEntry e : golden) {
                List<SearchHit> hits = searchService.search(backend, e.question(), TOP_K);
                int rank = rankOfExpected(hits, e); // 1-based, 0 = not found
                if (rank >= 1 && rank <= 5) recall5++;
                if (rank >= 1) mrr += 1.0 / rank;
                if (rank == 1) hit1++;
            }
            int n = golden.size();
            System.out.printf("%-10s %10.3f %10.3f %10.3f%n",
                    backend, recall5 / n, mrr / n, hit1 / n);
        }
    }

    /** Ingest every markdown file under docs/ with the markdown chunker. Returns doc count. */
    private int ingestCorpus() throws Exception {
        int count = 0;
        try (Stream<Path> paths = Files.walk(Path.of("docs"))) {
            for (Path p : paths.filter(p -> p.toString().endsWith(".md")).toList()) {
                String name = p.getFileName().toString();
                String docId = name.substring(0, name.length() - 3).replaceAll("[^a-zA-Z0-9._-]", "-");
                ingestService.ingestMarkdown(docId, name, Files.readString(p));
                count++;
            }
        }
        // README.md at repo root is part of the corpus too
        ingestService.ingestMarkdown("README", "README.md", Files.readString(Path.of("README.md")));
        return count + 1;
    }

    private static int rankOfExpected(List<SearchHit> hits, GoldenEntry e) {
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            boolean docMatch = h.docId().equals(e.expectedDocId());
            boolean headingMatch = e.expectedHeadingPath() == null
                    || (h.headingPath() != null && h.headingPath().startsWith(e.expectedHeadingPath()));
            if (docMatch && headingMatch) {
                return i + 1;
            }
        }
        return 0;
    }
}
