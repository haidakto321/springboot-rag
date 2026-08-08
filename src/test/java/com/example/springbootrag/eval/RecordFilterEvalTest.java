package com.example.springbootrag.eval;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.repository.MetadataFilter;
import com.example.springbootrag.repository.ProjectRepository;
import com.example.springbootrag.repository.RecordCountRepository;
import com.example.springbootrag.security.TestContexts;
import com.example.springbootrag.service.RecordIngestService;
import com.example.springbootrag.service.SearchService;
import com.example.springbootrag.understand.QueryRouter;
import com.example.springbootrag.understand.QueryUnderstanding;
import com.example.springbootrag.understand.Route;
import com.example.springbootrag.web.dto.RecordRequest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reports what query understanding is worth on a corpus that exists on every machine.
 *
 * <p>Unlike {@code WikiRetrievalEvalTest} this uses Testcontainers and a committed synthetic
 * corpus, so it runs on a fresh clone and in CI. It reports, it does not gate - the same order
 * drill C followed before turning the wiki eval into a regression gate.
 *
 * <p>Run: ./mvnw test "-Dgroups=eval-records" "-DexcludedGroups="
 */
@SpringBootTest(properties = {"app.graph.edges=structural", "app.understand.facet-ttl-seconds=0"})
@Testcontainers
@Tag("eval-records")
class RecordFilterEvalTest {

    private static final long SEED = 42;
    /**
     * Wider than the wiki gate's 0.02: retrieval there is deterministic, extraction here is a
     * sampled model whose output moves between runs. Too tight and the gate cries wolf, which ends
     * with someone passing -Deval.baseline.update=true to make it stop - the worst outcome.
     */
    private static final double TOLERANCE = 0.05;
    private static final int TOP_K = 10;

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

    /**
     * Fake embeddings on purpose. Embedding 210 records with a real model takes hours, and what
     * this eval measures is the FILTER, not embedding quality - the printed header says so, because
     * a recall number quoted without that caveat is how "hybrid beats vector" became folklore.
     */
    @TestConfiguration
    static class FakeEmbeddingConfig {
        @Bean @Primary
        EmbeddingProvider fakeEmbeddingProvider() {
            return new EmbeddingProvider() {
                @Override public float[] embed(String text) {
                    // Deterministic per-text vector: not semantic, just stable and not all-equal.
                    float[] v = new float[768];
                    int h = text.hashCode();
                    for (int i = 0; i < v.length; i++) {
                        h = h * 31 + i;
                        v[i] = ((h % 1000) / 1000f);
                    }
                    return v;
                }

                @Override public int dimension() { return 768; }
            };
        }
    }

    @Autowired RecordIngestService recordIngest;
    @Autowired ProjectRepository projectRepository;
    @Autowired SearchService searchService;
    @Autowired QueryUnderstanding understanding;
    @Autowired QueryRouter router;
    @Autowired RecordCountRepository counts;
    @Autowired ChatProvider chat;

    private static Long projectId;

    @BeforeEach
    void seedOnce() {
        // Extraction needs a live model; skip rather than fail when Ollama is not running, the same
        // way the wiki eval skips a missing corpus.
        try {
            chat.chat("Reply with the single word ok.", "ping", understanding.model());
        } catch (RuntimeException e) {
            Assumptions.abort("no chat model reachable for extraction: " + e.getMessage());
        }
        if (projectId != null) return;
        long id = projectRepository.create("eval-records-" + SEED, null);
        List<RecordRequest> corpus = RecordCorpus.generate(SEED);
        System.out.printf(Locale.ROOT, "[eval-records] seeding %d records...%n", corpus.size());
        for (RecordRequest r : corpus) {
            recordIngest.ingest(id, r);
        }
        System.out.println("[eval-records] seeded. Extraction is one LIVE model call per question "
                + "on " + understanding.model() + " - expect minutes each on CPU.");
        System.out.flush();
        projectId = id;
    }

    @Test
    void queryUnderstandingReport() {
        List<RecordGoldenEntry> golden = RecordGoldenSet.load();
        List<RecordRequest> corpus = RecordCorpus.generate(SEED);

        int expectedConditions = 0, extractedConditions = 0, matchedConditions = 0;
        int docTypeQuestions = 0, docTypeCorrect = 0;
        int noFilterQuestions = 0, noFilterCorrect = 0;
        int widened = 0, expectedWiden = 0, expectedWidenSeen = 0;
        int routedCorrectly = 0, aggregateQuestions = 0, aggregateCountCorrect = 0;
        List<Long> latencies = new ArrayList<>();
        List<Long> routeLatencies = new ArrayList<>();
        List<String> routes = new ArrayList<>();

        List<Integer> ranksWith = new ArrayList<>();
        List<Integer> ranksWithout = new ArrayList<>();
        List<String> filteredQuestions = new ArrayList<>();
        StringBuilder perQuestion = new StringBuilder();

        for (int q = 0; q < golden.size(); q++) {
            RecordGoldenEntry entry = golden.get(q);
            // Printed as it goes, not buffered to the end: a run that takes minutes per question
            // must never be indistinguishable from a hung one.
            System.out.printf(Locale.ROOT, "[eval-records] %d/%d asking: %s%n",
                    q + 1, golden.size(), entry.question());
            System.out.flush();

            QueryRouter.Decision decision = router.route(entry.question());
            String route = decision.route().label();
            routes.add(route);
            routeLatencies.add(decision.latencyMs());
            if (route.equals(entry.expectedRoute())) routedCorrectly++;
            System.out.printf(Locale.ROOT, "[eval-records]   route=%s (%s, %d ms), expected %s%n",
                    route, decision.source(), decision.latencyMs(), entry.expectedRoute());
            System.out.flush();

            // Chit-chat skips extraction because the feature skips it. Scoring extraction on a
            // question that never reaches the extractor would measure a path that no longer runs.
            QueryUnderstanding.Extraction extraction = decision.route() == Route.CHITCHAT
                    ? QueryUnderstanding.Extraction.none()
                    : understanding.extract(TestContexts.PUBLIC, List.of(projectId), entry.question());
            if (decision.route() != Route.CHITCHAT) latencies.add(extraction.latencyMs());
            MetadataFilter got = extraction.filter();
            // The filter and the drop reasons, not just a count: when a condition disappears, the
            // reason is the whole diagnosis, and without it the next step is guesswork against a
            // 30-minute feedback loop.
            System.out.printf(Locale.ROOT, "[eval-records]   -> %d ms, docType=%s, filter=%s%n",
                    extraction.latencyMs(), got.docType(),
                    com.example.springbootrag.understand.FilterJson.toApiString(got));
            if (!extraction.dropped().isEmpty()) {
                System.out.printf(Locale.ROOT, "[eval-records]      DROPPED: %s%n",
                        String.join("; ", extraction.dropped()));
            }
            System.out.flush();

            // ---- extraction quality ----
            if (!got.isEmpty()) filteredQuestions.add(entry.question());
            expectedConditions += entry.expectedFilters().size();
            extractedConditions += got.conditions().size();
            for (Map<String, Object> want : entry.expectedFilters()) {
                if (got.conditions().stream().anyMatch(c -> sameCondition(c, want))) {
                    matchedConditions++;
                }
            }
            if (entry.expectedDocType() != null) {
                docTypeQuestions++;
                if (entry.expectedDocType().equals(got.docType())) docTypeCorrect++;
            }
            if (entry.expectNoFilter()) {
                noFilterQuestions++;
                if (got.isEmpty()) noFilterCorrect++;
            }
            if (entry.expectWiden()) expectedWiden++;

            // ---- aggregate questions are scored on the number, not on the prose ----
            if ("aggregate".equals(entry.expectedRoute())) {
                aggregateQuestions++;
                long expectedCount = RecordGroundTruth.matchingDocIds(corpus, entry).size();
                long actualCount = counts.count(TestContexts.PUBLIC, List.of(projectId), got);
                if (expectedCount == actualCount) aggregateCountCorrect++;
                System.out.printf(Locale.ROOT, "[eval-records]   count=%d, ground truth %d%n",
                        actualCount, expectedCount);
            }

            // ---- retrieval, with the extracted filter and without ----
            if (decision.route() == Route.CHITCHAT) {
                perQuestion.append(String.format(Locale.ROOT,
                        "  %-60s route=%s%n", entry.question(), route));
                continue;   // no retrieval happens on this route, so there is nothing to score
            }
            List<SearchHit> with = searchService.search(TestContexts.PUBLIC, "rerank",
                    entry.question(), TOP_K, List.of(projectId), List.of(), got);
            boolean didWiden = with.isEmpty() && !got.isEmpty();
            if (didWiden) {
                widened++;
                if (entry.expectWiden()) expectedWidenSeen++;
                with = searchService.search(TestContexts.PUBLIC, "rerank", entry.question(),
                        TOP_K, List.of(projectId), List.of(), MetadataFilter.none());
            }
            List<SearchHit> without = searchService.search(TestContexts.PUBLIC, "rerank",
                    entry.question(), TOP_K, List.of(projectId), List.of(), MetadataFilter.none());

            // Only questions with a defined correct answer contribute to recall/MRR: for a
            // "no filter expected" question every document is arguably right, and scoring it would
            // reward the system for the case it cannot get wrong.
            if (!entry.expectedFilters().isEmpty() && !entry.expectWiden()) {
                List<String> correct = RecordGroundTruth.matchingDocIds(corpus, entry);
                ranksWith.add(firstCorrectRank(with, correct));
                ranksWithout.add(firstCorrectRank(without, correct));
            }
            perQuestion.append(String.format(Locale.ROOT,
                    "  %-60s route=%-9s docType=%-14s conditions=%d%s%n",
                    entry.question(), route, String.valueOf(got.docType()),
                    got.conditions().size(), didWiden ? " WIDENED" : ""));
        }

        BackendMetrics withMetrics = BackendMetrics.of(toArray(ranksWith), ranksWith.size());
        BackendMetrics withoutMetrics = BackendMetrics.of(toArray(ranksWithout), ranksWithout.size());

        System.out.printf(Locale.ROOT, "%n=== query understanding, %d questions, corpus %d records ===%n",
                golden.size(), corpus.size());
        System.out.println("NOTE: embeddings are FAKE in this eval. The recall/MRR figures below");
        System.out.println("      measure the metadata FILTER, not embedding or reranker quality.");
        System.out.printf(Locale.ROOT, "condition precision   %.2f   (matched %d / extracted %d)%n",
                ratio(matchedConditions, extractedConditions), matchedConditions, extractedConditions);
        System.out.printf(Locale.ROOT, "condition recall      %.2f   (matched %d / expected %d)%n",
                ratio(matchedConditions, expectedConditions), matchedConditions, expectedConditions);
        System.out.printf(Locale.ROOT, "docType accuracy      %.2f   (%d/%d)%n",
                ratio(docTypeCorrect, docTypeQuestions), docTypeCorrect, docTypeQuestions);
        System.out.printf(Locale.ROOT, "no-filter questions   %d/%d correctly left unfiltered%n",
                noFilterCorrect, noFilterQuestions);
        System.out.printf(Locale.ROOT, "widen rate            %d/%d   (%d of %d expected-widen questions did)%n",
                widened, golden.size(), expectedWidenSeen, expectedWiden);
        System.out.printf(Locale.ROOT, "extraction p50        %d ms   (model: %s)%n",
                median(latencies), understanding.model());
        System.out.printf(Locale.ROOT, "route accuracy        %.2f   (%d/%d)%n",
                ratio(routedCorrectly, golden.size()), routedCorrectly, golden.size());
        System.out.printf(Locale.ROOT, "aggregate counts      %d/%d exactly right%n",
                aggregateCountCorrect, aggregateQuestions);
        System.out.printf(Locale.ROOT, "router p50            %d ms   (model: %s)%n",
                median(routeLatencies), router.model());
        System.out.printf(Locale.ROOT, "%nscored on %d filter questions%n", ranksWith.size());
        System.out.printf(Locale.ROOT, "recall@5   with extraction %.2f   without %.2f%n",
                withMetrics.recall5(), withoutMetrics.recall5());
        System.out.printf(Locale.ROOT, "MRR        with extraction %.2f   without %.2f%n",
                withMetrics.mrr(), withoutMetrics.mrr());
        System.out.printf(Locale.ROOT, "hit@1      with extraction %.2f   without %.2f%n%n",
                withMetrics.hit1(), withoutMetrics.hit1());
        System.out.println("per question:");
        System.out.print(perQuestion);

        RecordEvalBaseline measured = new RecordEvalBaseline(SEED, corpus.size(),
                golden.stream().map(RecordGoldenEntry::question).toList(),
                new RecordEvalBaseline.Extraction(
                        ratio(matchedConditions, extractedConditions),
                        ratio(matchedConditions, expectedConditions),
                        ratio(docTypeCorrect, docTypeQuestions),
                        noFilterCorrect),
                Map.of("with-extraction", withMetrics, "without-extraction", withoutMetrics),
                List.copyOf(filteredQuestions),
                List.copyOf(routes),
                new RecordEvalBaseline.Routing(ratio(routedCorrectly, golden.size()),
                        aggregateCountCorrect));
        gate(measured);
    }

    /**
     * Compares this run against the committed baseline, or writes a new one.
     *
     * <p>Extraction quality is not deterministic - the model samples, and its output drifts with a
     * version bump - so the gate is a floor with a tolerance, never an equality check. What it is
     * really defending is the class of failure this feature already shipped once: a prompt change
     * that quietly takes condition recall from 0.73 to 0.07 while every unit test stays green.
     */
    private void gate(RecordEvalBaseline measured) {
        if (Boolean.getBoolean("eval.baseline.update")) {
            RecordEvalBaselineStore.write(measured);
            System.out.printf(Locale.ROOT, "%nbaseline WRITTEN to %s - review the diff before "
                    + "committing it, a baseline is a claim about what is correct%n",
                    RecordEvalBaselineStore.SOURCE);
            return;
        }
        if (!RecordEvalBaselineStore.exists()) {
            System.out.printf(Locale.ROOT, "%nno baseline yet - create one with "
                    + "-Deval.baseline.update=true%n");
            return;
        }
        RecordEvalBaseline expected = RecordEvalBaselineStore.load();
        List<String> added = RecordEvalComparison.newQuestions(expected, measured);
        if (!added.isEmpty()) {
            System.out.printf(Locale.ROOT, "%nnew questions since the baseline (not gated): %s%n",
                    String.join(", ", added));
        }
        List<RecordEvalComparison.Violation> violations =
                RecordEvalComparison.compare(expected, measured, TOLERANCE);
        assertThat(violations)
                .as("query understanding regressed against %s:%n%s",
                        RecordEvalBaselineStore.RESOURCE,
                        violations.stream().map(v -> "  [" + v.area() + "] " + v.detail())
                                .collect(java.util.stream.Collectors.joining("\n")))
                .isEmpty();
    }

    /** Path and op must be equal; text values compare case-insensitively, numbers numerically. */
    private static boolean sameCondition(MetadataFilter.Condition got, Map<String, Object> want) {
        if (!got.path().equals(want.get("path")) || !got.op().equals(want.get("op"))) return false;
        return switch (got.op()) {
            case "exists" -> true;
            case "eq" -> looseEquals(got.value(), want.get("value"));
            case "in" -> {
                List<?> wanted = (List<?>) want.getOrDefault("values", List.of());
                yield got.values().size() == wanted.size()
                        && got.values().stream().allMatch(
                                v -> wanted.stream().anyMatch(w -> looseEquals(v, w)));
            }
            case "range" -> looseEquals(got.gte(), want.get("gte")) && looseEquals(got.gt(), want.get("gt"))
                    && looseEquals(got.lte(), want.get("lte")) && looseEquals(got.lt(), want.get("lt"));
            default -> false;
        };
    }

    private static boolean looseEquals(Object a, Object b) {
        if (a == null || b == null) return a == null && b == null;
        if (a instanceof Number x && b instanceof Number y) {
            return x.doubleValue() == y.doubleValue();
        }
        String sa = String.valueOf(a);
        String sb = String.valueOf(b);
        if (sa.equalsIgnoreCase(sb)) return true;
        try {
            return Double.parseDouble(sa) == Double.parseDouble(sb);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 1-based rank of the first hit that is genuinely a correct document, 0 when none is. */
    private static int firstCorrectRank(List<SearchHit> hits, List<String> correct) {
        for (int i = 0; i < hits.size(); i++) {
            if (correct.contains(hits.get(i).docId())) return i + 1;
        }
        return 0;
    }

    private static int[] toArray(List<Integer> values) {
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) out[i] = values.get(i);
        return out;
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private static long median(List<Long> values) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(values);
        java.util.Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }
}
