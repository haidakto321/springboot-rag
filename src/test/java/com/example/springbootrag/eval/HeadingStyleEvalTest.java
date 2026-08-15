package com.example.springbootrag.eval;

import com.example.springbootrag.chunk.HeadingStyle;
import com.example.springbootrag.config.ChunkProperties;
import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.security.TestContexts;
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
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prices every heading-breadcrumb style against the golden set. NOT part of the normal build.
 * Prereqs: Docker running, Ollama at localhost:11434 with nomic-embed-text pulled.
 * Run: ./mvnw test "-Dgroups=eval-heading" "-DexcludedGroups="
 *
 * <p>Cost: five full corpus ingests against real Ollama. Ollama timing swings hard under memory
 * pressure - reap stray JVMs and containers first or the numbers are noise.
 */
// The docs corpus documents the sandbox's own fake credentials (docs/ARCHITECTURE.md), so the
// credential quarantine refuses it at ingest. This is the deliberate-bulk-import case the flag was
// built for - see GuardProperties.Quarantine. Turning it off here measures retrieval over the whole
// corpus; it does not weaken the control anywhere else.
@SpringBootTest(properties = "app.guard.quarantine.enabled=false")
@Testcontainers
@Tag("eval-heading")
class HeadingStyleEvalTest {

    static final List<String> BACKENDS = List.of("fts", "pgvector", "qdrant", "hybrid", "rerank", "graph");
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
    @Autowired ChunkProperties chunkProps;

    @Test
    void headingStyleComparisonReport() throws Exception {
        List<GoldenEntry> golden = GoldenSet.load();
        assertThat(golden).isNotEmpty();

        System.out.printf("%nHeading style eval: %d questions, topK=%d%n", golden.size(), TOP_K);
        System.out.printf("%-12s %-10s %10s %10s %10s%n", "style", "backend", "recall@5", "MRR", "hit@1");

        for (HeadingStyle style : HeadingStyle.values()) {
            chunkProps.setHeadingStyle(style);
            int docs = ingestCorpus();   // re-ingest: the embedded text changed
            assertThat(docs).isGreaterThan(0);

            for (String backend : BACKENDS) {
                double recall5 = 0, mrr = 0, hit1 = 0;
                for (GoldenEntry e : golden) {
                    List<SearchHit> hits =
                            searchService.search(TestContexts.PUBLIC, backend, e.question(), TOP_K);
                    int rank = rankOfExpected(hits, e); // 1-based, 0 = not found
                    if (rank >= 1 && rank <= 5) recall5++;
                    if (rank >= 1) mrr += 1.0 / rank;
                    if (rank == 1) hit1++;
                }
                int n = golden.size();
                System.out.printf(Locale.ROOT, "%-12s %-10s %10.3f %10.3f %10.3f%n",
                        style.name().toLowerCase(Locale.ROOT), backend,
                        recall5 / n, mrr / n, hit1 / n);
            }
        }
    }

    /** Ingest every markdown file under docs/ plus the root README. Returns doc count. */
    private int ingestCorpus() throws Exception {
        int count = 0;
        try (Stream<Path> paths = Files.walk(Path.of("docs"))) {
            for (Path p : paths.filter(p -> p.toString().endsWith(".md")).toList()) {
                String rel = Path.of("docs").relativize(p).toString();
                String docId = rel.substring(0, rel.length() - 3).replaceAll("[^a-zA-Z0-9._-]", "-");
                String name = p.getFileName().toString();
                ingestService.ingestMarkdown(docId, name, Files.readString(p));
                count++;
            }
        }
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
