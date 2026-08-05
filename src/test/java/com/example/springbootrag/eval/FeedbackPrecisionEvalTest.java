package com.example.springbootrag.eval;

import com.example.springbootrag.model.FeedbackLabel;
import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.repository.FeedbackRepository;
import com.example.springbootrag.rerank.Reranker;
import com.example.springbootrag.service.SearchService;
import com.example.springbootrag.security.TestContexts;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Precision report over HUMAN labels collected through the UI thumbs (POST /feedback).
 *
 * <p>The golden sets answer "did we retrieve the one page we already know is right". This answers
 * a different question on real usage: "of what each backend actually shows a user, how much did a
 * human call relevant, and does the reranker push the good chunks up". That is the only way to
 * find out whether {@code DjlReranker} earns its latency on THIS corpus - see RAG-MASTERY
 * section 3 drill D.
 *
 * <p>Like {@link WikiRetrievalEvalTest} this declares no Testcontainers and reads the LIVE local
 * stack, and is read-only by construction (SearchService + FeedbackRepository reads only). It
 * SKIPS instead of failing when there are too few labels, because a fresh clone has none.
 *
 * <p>A report, not a gate: labels accumulate over time, so any threshold committed today would
 * fail tomorrow for an honest reason.
 *
 * <p>Run: ./mvnw test "-Dgroups=eval-feedback" "-DexcludedGroups="
 * (cross-encoder run: add -Deval.rerank=djl)
 */
@SpringBootTest
@Tag("eval-feedback")
@TestPropertySource(properties = "spring.sql.init.mode=never")
class FeedbackPrecisionEvalTest {

    static final int TOP_K = 10;
    static final List<String> BACKENDS =
            List.of("fts", "pgvector", "qdrant", "hybrid", "rerank", "graph");

    /** Below this, precision@k is noise. Override with -Deval.feedback.min=N. */
    static final int DEFAULT_MIN_LABELS = 10;

    @Autowired SearchService searchService;
    @Autowired FeedbackRepository feedback;
    @Autowired Reranker reranker;

    /** Same switch as the wiki eval: -Deval.rerank=djl maps onto app.rerank.provider. */
    @DynamicPropertySource
    static void rerankOverride(DynamicPropertyRegistry registry) {
        registry.add("app.rerank.provider", () -> System.getProperty("eval.rerank", ""));
    }

    @Test
    void feedbackPrecisionReport() {
        List<LabelledQuery> queries = requireLabels();

        int labelCount = queries.stream().mapToInt(q -> q.labels().size()).sum();
        System.out.printf("%nFeedback precision eval: %d labels over %d distinct queries, topK=%d%n",
                labelCount, queries.size(), TOP_K);
        System.out.printf("reranker=%s%n", reranker.getClass().getSimpleName());

        Map<String, Score> scores = new LinkedHashMap<>();
        for (String backend : BACKENDS) {
            scores.put(backend, scoreBackend(backend, queries));
        }

        printReport(scores, queries.size());
        printCoverageWarning(queries, scores);

        // Labels that match nothing any backend returns mean the corpus moved under them
        // (re-ingest, different project, deleted document) - a table of zeros would hide that.
        assertThat(scores.values().stream().mapToInt(Score::judged).sum())
                .as("no labelled chunk appeared in the top %d of ANY backend - the labels were "
                        + "probably collected against a corpus that has since been re-ingested", TOP_K)
                .isPositive();
    }

    /** One query plus every chunk a human judged for it. Key: "docId#chunkIndex" -> relevant. */
    record LabelledQuery(long projectId, String query, Map<String, Boolean> labels) {}

    /**
     * Running totals for one backend.
     *
     * @param sumP5     summed precision@5, over queries with at least one judged hit in the top 5
     * @param scoredP5  how many queries contributed to sumP5
     * @param sumRr     summed reciprocal rank of the first thumbs-up chunk (0 when none in top K)
     * @param judged    labelled chunks seen in the top K, across all queries
     * @param up        of those, how many were thumbs-up
     * @param returned  hits returned in total, judged or not
     */
    record Score(double sumP5, int scoredP5, double sumP10, int scoredP10,
                 double sumRr, int judged, int up, int returned) {

        double p5() { return scoredP5 == 0 ? 0 : sumP5 / scoredP5; }
        double p10() { return scoredP10 == 0 ? 0 : sumP10 / scoredP10; }
        double mrrUp(int queryCount) { return queryCount == 0 ? 0 : sumRr / queryCount; }
        double coverage() { return returned == 0 ? 0 : (double) judged / returned; }
    }

    private Score scoreBackend(String backend, List<LabelledQuery> queries) {
        double sumP5 = 0, sumP10 = 0, sumRr = 0;
        int scoredP5 = 0, scoredP10 = 0, judged = 0, up = 0, returned = 0;

        for (LabelledQuery q : queries) {
            List<SearchHit> hits = searchService.search(TestContexts.PUBLIC,
                    backend, q.query(), TOP_K, List.of(q.projectId()), List.of());
            returned += hits.size();

            int judged5 = 0, up5 = 0, judged10 = 0, up10 = 0;
            double rr = 0;
            for (int i = 0; i < hits.size(); i++) {
                Boolean relevant = q.labels().get(key(hits.get(i)));
                if (relevant == null) continue;          // unjudged hits are ignored, not counted wrong
                judged10++;
                if (relevant) up10++;
                if (i < 5) {
                    judged5++;
                    if (relevant) up5++;
                }
                if (relevant && rr == 0) rr = 1.0 / (i + 1);
            }

            judged += judged10;
            up += up10;
            sumRr += rr;
            if (judged5 > 0) { sumP5 += (double) up5 / judged5; scoredP5++; }
            if (judged10 > 0) { sumP10 += (double) up10 / judged10; scoredP10++; }
        }
        return new Score(sumP5, scoredP5, sumP10, scoredP10, sumRr, judged, up, returned);
    }

    private static String key(SearchHit hit) {
        return hit.docId() + "#" + hit.chunkIndex();
    }

    /**
     * precision@k is computed over JUDGED hits only. With sparse human labels the alternative -
     * treating every unlabelled hit as irrelevant - would just measure how much someone clicked.
     */
    private static void printReport(Map<String, Score> scores, int queryCount) {
        System.out.printf("%n%-10s %8s %8s %9s %9s %9s%n",
                "backend", "P@5", "P@10", "MRR(up)", "judged", "coverage");
        scores.forEach((backend, s) -> System.out.printf(Locale.ROOT,
                "%-10s %8.3f %8.3f %9.3f %9d %9.3f%n",
                backend, s.p5(), s.p10(), s.mrrUp(queryCount), s.judged(), s.coverage()));
        System.out.println("P@k over judged hits only; coverage = judged / returned. "
                + "MRR(up) = mean reciprocal rank of the first thumbs-up chunk, over all queries.");
    }

    private static void printCoverageWarning(List<LabelledQuery> queries, Map<String, Score> scores) {
        int best = scores.values().stream().mapToInt(Score::judged).max().orElse(0);
        if (best < queries.size()) {
            System.out.printf("%nnotice: the best backend saw only %d labelled chunks across %d "
                            + "queries - collect more thumbs before trusting these numbers%n",
                    best, queries.size());
        }
    }

    /**
     * Groups stored labels by (project, query). SKIPS when the stack is down or the label set is
     * too thin to say anything, which is the normal state of a fresh clone.
     */
    private List<LabelledQuery> requireLabels() {
        int min = Integer.getInteger("eval.feedback.min", DEFAULT_MIN_LABELS);

        List<FeedbackLabel> labels;
        try {
            labels = feedback.list(TestContexts.PUBLIC, null, null, 1000);
        } catch (DataAccessException e) {
            return Assumptions.abort(
                    "Postgres is not reachable (or chunk_feedback does not exist yet - start the "
                            + "app once so schema.sql runs). Cause: " + e.getMessage());
        }

        Assumptions.assumeTrue(labels.size() >= min,
                "only " + labels.size() + " chunk labels stored, need " + min
                        + " - click the thumbs on search results and answer citations first, "
                        + "or lower the bar with -Deval.feedback.min=N");

        Map<String, LabelledQuery> grouped = new LinkedHashMap<>();
        for (FeedbackLabel l : labels) {
            grouped.computeIfAbsent(l.projectId() + " " + l.query(),
                    k -> new LabelledQuery(l.projectId(), l.query(), new LinkedHashMap<>()))
                    .labels().put(l.docId() + "#" + l.chunkIndex(), l.relevant());
        }
        return new ArrayList<>(grouped.values());
    }
}
