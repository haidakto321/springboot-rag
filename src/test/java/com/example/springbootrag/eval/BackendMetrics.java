package com.example.springbootrag.eval;

/**
 * One backend's aggregate retrieval quality over a golden set.
 *
 * <p>The single place these three numbers are computed. The printed report and the regression
 * baseline both call {@link #of}, so they cannot drift apart.
 */
public record BackendMetrics(double recall5, double mrr, double hit1) {

    /**
     * @param ranks 1-based rank of the expected document per question, 0 when it was not found
     * @param questionCount the golden set size, used as the denominator for all three metrics
     */
    public static BackendMetrics of(int[] ranks, int questionCount) {
        double recall5 = 0, mrr = 0, hit1 = 0;
        for (int rank : ranks) {
            if (rank >= 1 && rank <= 5) recall5++;
            if (rank >= 1) mrr += 1.0 / rank;
            if (rank == 1) hit1++;
        }
        return new BackendMetrics(recall5 / questionCount, mrr / questionCount, hit1 / questionCount);
    }
}
