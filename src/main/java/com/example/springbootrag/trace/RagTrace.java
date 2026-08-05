package com.example.springbootrag.trace;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything that produced one answer.
 *
 * <p>The failure mode this exists for: a wrong answer throws nothing. The decision chain - which
 * query was actually searched after condensing, which chunks came back at what score, which stage
 * ate the latency - is invisible unless it is written down at the time.
 *
 * @param retrieved      what retrieval returned, in rank order
 * @param stageLatencyMs per-stage milliseconds; keys are stage names, so a new stage does not need
 *                       a migration
 * @param guardReason    the {@code AnswerGuard} verdict reason, so a refused answer is
 *                       distinguishable from a model that had nothing to say
 */
public record RagTrace(
        UUID requestId,
        Instant ts,
        String principal,
        List<Long> projectIds,
        String rawQuery,
        String condensedQuery,
        String backend,
        List<Retrieved> retrieved,
        Map<String, Long> stageLatencyMs,
        Integer promptTokens,
        Integer completionTokens,
        String answer,
        String guardReason
) {

    /** One retrieved chunk, identified the same way feedback labels are: doc id plus index. */
    public record Retrieved(String docId, int chunkIndex, double score) {}
}
