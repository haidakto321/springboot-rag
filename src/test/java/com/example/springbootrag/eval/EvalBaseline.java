package com.example.springbootrag.eval;

import java.util.List;
import java.util.Map;

/**
 * One measured or expected eval result set for a single reranker variant.
 *
 * @param corpus which corpus this was measured on, used to detect a stale baseline
 * @param variant reranker variant name: "identity" or "djl"
 * @param questions the whole golden set in file order. Recorded in full, not only the found ones,
 *                  so a newly added question can be told apart from one that was always missed.
 * @param metrics backend name to its aggregate metrics
 * @param found backend name to the questions whose expected document it found (rank > 0)
 */
public record EvalBaseline(
        CorpusFingerprint corpus,
        String variant,
        List<String> questions,
        Map<String, BackendMetrics> metrics,
        Map<String, List<String>> found) {}
