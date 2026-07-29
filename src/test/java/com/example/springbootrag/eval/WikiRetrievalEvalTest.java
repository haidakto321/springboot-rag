package com.example.springbootrag.eval;

import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.repository.ProjectRepository;
import com.example.springbootrag.service.SearchService;
import com.example.springbootrag.web.dto.ProjectSummary;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retrieval quality report for the imported wiki corpus (golden-wiki.yaml).
 *
 * <p>Unlike {@code RetrievalEvalTest} this builds NO corpus of its own: the wiki clone is private
 * and re-embedding 7,536 chunks per run costs hours. It therefore declares no Testcontainers, so
 * Spring uses application.yml and queries the LIVE local stack, reading whatever is already
 * imported.
 *
 * <p>READ-ONLY BY CONSTRUCTION: only SearchService and ProjectRepository are injected, so there is
 * no code path here that can write or delete. Do NOT add IngestService to this class.
 *
 * <p>Prereqs: Postgres 5432 + Qdrant 6334 up, Ollama 11434 with nomic-embed-text pulled, and the
 * wiki already imported into a project named "docmaster" (override with -Deval.wiki.project=NAME).
 *
 * <p>Run: ./mvnw test "-Dgroups=eval-wiki" "-DexcludedGroups="
 */
@SpringBootTest
@Tag("eval-wiki")
@TestPropertySource(properties = "spring.sql.init.mode=never")
class WikiRetrievalEvalTest {

    static final String GOLDEN = "/eval/golden-wiki.yaml";
    static final int TOP_K = 10;
    static final List<String> BACKENDS =
            List.of("fts", "pgvector", "qdrant", "hybrid", "rerank", "graph");

    @Autowired SearchService searchService;
    @Autowired ProjectRepository projects;

    @Test
    void wikiRetrievalReport() {
        ProjectSummary project = requireCorpus();
        List<GoldenEntry> golden = GoldenSet.load(GOLDEN);
        assertThat(golden).isNotEmpty();

        System.out.printf("%nWiki retrieval eval: project=%s (id=%d, %d docs, %d chunks), "
                        + "%d questions, topK=%d%n",
                project.name(), project.id(), project.docCount(), project.chunkCount(),
                golden.size(), TOP_K);

        List<BackendRun> runs = runAll(golden, project.id());

        printAggregate(runs, golden.size());
        printMatrix(golden, runs);
        printGraphVsHybrid(golden, runs);

        // A run that quietly returns nothing must fail, not print a table of zeros.
        // Per backend, not across backends: one healthy backend must not mask five broken ones.
        assertThat(runs).allSatisfy(run ->
                assertThat(Arrays.stream(run.ranks()).anyMatch(r -> r > 0))
                        .as("backend '%s' found no golden doc for any question", run.backend())
                        .isTrue());
    }

    /** One backend's full sweep: hits per question, plus the rank of the expected doc. */
    record BackendRun(String backend, List<List<SearchHit>> hits, int[] ranks) {}

    /** Runs every golden question through every backend once, scoped to the corpus project. */
    private List<BackendRun> runAll(List<GoldenEntry> golden, long projectId) {
        List<BackendRun> runs = new ArrayList<>();
        for (String backend : BACKENDS) {
            List<List<SearchHit>> hits = new ArrayList<>();
            int[] ranks = new int[golden.size()];
            for (int i = 0; i < golden.size(); i++) {
                GoldenEntry entry = golden.get(i);
                List<SearchHit> result = searchService.search(
                        backend, entry.question(), TOP_K, List.of(projectId), List.of());
                hits.add(result);
                ranks[i] = rankOfExpected(result, entry);
            }
            runs.add(new BackendRun(backend, hits, ranks));
        }
        return runs;
    }

    /**
     * 1-based rank of the expected document, 0 when absent from the top K.
     * Same rule as RetrievalEvalTest: docId must match and, when the golden entry pins a heading
     * path, the hit's heading path must start with it.
     */
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

    /** recall@5 is counted inside the first 5 of the TOP_K fetched, matching RetrievalEvalTest. */
    private static void printAggregate(List<BackendRun> runs, int questionCount) {
        System.out.printf("%n%-10s %10s %10s %10s%n", "backend", "recall@5", "MRR", "hit@1");
        for (BackendRun run : runs) {
            double recall5 = 0, mrr = 0, hit1 = 0;
            for (int rank : run.ranks()) {
                if (rank >= 1 && rank <= 5) recall5++;
                if (rank >= 1) mrr += 1.0 / rank;
                if (rank == 1) hit1++;
            }
            System.out.printf(Locale.ROOT, "%-10s %10.3f %10.3f %10.3f%n",
                    run.backend(), recall5 / questionCount, mrr / questionCount, hit1 / questionCount);
        }
    }

    /** One row per question, one column per backend, showing the rank of the expected doc. */
    private static void printMatrix(List<GoldenEntry> golden, List<BackendRun> runs) {
        System.out.printf("%nrank of expected doc per question (0 = miss)%n");
        System.out.printf("%-46s", "question");
        for (BackendRun run : runs) {
            System.out.printf(" %8s", run.backend());
        }
        System.out.println();

        for (int i = 0; i < golden.size(); i++) {
            System.out.printf("%-46s", truncate(golden.get(i).question(), 44));
            for (BackendRun run : runs) {
                System.out.printf(" %8d", run.ranks()[i]);
            }
            System.out.println();
        }
    }

    /** Identity of a result list: the ordered (docId, chunkIndex) pairs. */
    private static List<String> keys(List<SearchHit> hits) {
        return hits.stream().map(h -> h.docId() + "#" + h.chunkIndex()).toList();
    }

    /**
     * Re-tests the LEARNINGS section 14 finding over the whole golden set instead of by hand.
     * Reports both comparisons and names every question where either one differs.
     */
    private static void printGraphVsHybrid(List<GoldenEntry> golden, List<BackendRun> runs) {
        BackendRun hybrid = runs.stream()
                .filter(r -> r.backend().equals("hybrid")).findFirst().orElseThrow();
        BackendRun graph = runs.stream()
                .filter(r -> r.backend().equals("graph")).findFirst().orElseThrow();

        int rankDiffers = 0;
        int identicalTop10 = 0;
        List<String> notes = new ArrayList<>();

        for (int i = 0; i < golden.size(); i++) {
            boolean sameOrder = keys(hybrid.hits().get(i)).equals(keys(graph.hits().get(i)));
            if (sameOrder) {
                identicalTop10++;
            }
            String question = truncate(golden.get(i).question(), 60);
            if (hybrid.ranks()[i] != graph.ranks()[i]) {
                rankDiffers++;
                notes.add(String.format("  %s: hybrid=rank %d, graph=rank %d%s",
                        question, hybrid.ranks()[i], graph.ranks()[i],
                        sameOrder ? "" : "  (top-10 order differs)"));
            } else if (!sameOrder) {
                notes.add(String.format("  %s: same rank %d, but top-10 order differs",
                        question, hybrid.ranks()[i]));
            }
        }

        System.out.printf("%ngraph vs hybrid: expected-doc rank differs on %d of %d; "
                        + "full top-10 identical on %d of %d%n",
                rankDiffers, golden.size(), identicalTop10, golden.size());
        notes.forEach(System.out::println);
    }

    private static String truncate(String s, int max) {
        if (max <= 3) {
            return s.substring(0, Math.max(0, Math.min(max, s.length())));
        }
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    /**
     * Resolves the corpus project by NAME (never a hardcoded id) and SKIPS the test when it is
     * absent. A fresh clone of this repo can never have the private wiki, so a hard failure there
     * would be permanent and meaningless.
     */
    private ProjectSummary requireCorpus() {
        String name = System.getProperty("eval.wiki.project", "docmaster");

        List<ProjectSummary> all;
        try {
            all = projects.listWithCounts();
        } catch (DataAccessException e) {
            return Assumptions.abort(
                    "Postgres is not reachable - start the stack before running the wiki eval. "
                            + "Looking for project '" + name + "'. Cause: " + e.getMessage());
        }

        Optional<ProjectSummary> found = all.stream()
                .filter(p -> p.name().equalsIgnoreCase(name))
                .findFirst();
        Assumptions.assumeTrue(found.isPresent(),
                "no project named '" + name + "' - import the wiki first, or pass "
                        + "-Deval.wiki.project=<name>. Projects present: "
                        + all.stream().map(ProjectSummary::name).toList());

        ProjectSummary project = found.get();
        Assumptions.assumeTrue(project.chunkCount() > 0,
                "project '" + name + "' has 0 chunks - nothing to evaluate");
        return project;
    }
}
