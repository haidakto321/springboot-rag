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
 * @param appliedFilter  the metadata filter that was actually used, as JSON - null when none was
 * @param filterWidened  true when the filter matched nothing and retrieval was retried without it
 * @param route          which path answered: chitchat, aggregate, or search
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
        String guardReason,
        String appliedFilter,
        boolean filterWidened,
        String route
) {

    /** A trace from a path that does no filtering: no filter, never widened, route unrecorded. */
    public RagTrace(UUID requestId, Instant ts, String principal, List<Long> projectIds,
                    String rawQuery, String condensedQuery, String backend,
                    List<Retrieved> retrieved, Map<String, Long> stageLatencyMs,
                    Integer promptTokens, Integer completionTokens, String answer,
                    String guardReason) {
        this(requestId, ts, principal, projectIds, rawQuery, condensedQuery, backend, retrieved,
                stageLatencyMs, promptTokens, completionTokens, answer, guardReason, null, false,
                null);
    }

    /** Pre-routing callers: the filter is recorded, the route is not known. */
    public RagTrace(UUID requestId, Instant ts, String principal, List<Long> projectIds,
                    String rawQuery, String condensedQuery, String backend,
                    List<Retrieved> retrieved, Map<String, Long> stageLatencyMs,
                    Integer promptTokens, Integer completionTokens, String answer,
                    String guardReason, String appliedFilter, boolean filterWidened) {
        this(requestId, ts, principal, projectIds, rawQuery, condensedQuery, backend, retrieved,
                stageLatencyMs, promptTokens, completionTokens, answer, guardReason, appliedFilter,
                filterWidened, null);
    }

    /** One retrieved chunk, identified the same way feedback labels are: doc id plus index. */
    public record Retrieved(String docId, int chunkIndex, double score) {}
}
