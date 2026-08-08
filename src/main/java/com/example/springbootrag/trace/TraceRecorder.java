package com.example.springbootrag.trace;

import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.security.SearchContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes one {@link RagTrace} per answered question.
 *
 * <p>Two rules make this safe to call from the answer path: it never throws (a broken trace must
 * not break a working answer), and it never blocks on anything slower than one INSERT.
 */
@Service
public class TraceRecorder {

    private static final Logger log = LoggerFactory.getLogger(TraceRecorder.class);

    private final TraceRepository repo;
    private final TraceProperties props;

    public TraceRecorder(TraceRepository repo, TraceProperties props) {
        this.repo = repo;
        this.props = props;
    }

    /** Registers the properties without adding another @EnableConfigurationProperties elsewhere. */
    @Configuration
    @EnableConfigurationProperties(TraceProperties.class)
    static class Props {}

    public boolean enabled() {
        return props.isEnabled();
    }

    /** Fire and forget. Returns the request id so a caller can hand it to the client. */
    public UUID record(UUID requestId, SearchContext ctx, List<Long> projectIds, String rawQuery,
                       String condensedQuery, String backend, List<SearchHit> hits,
                       Map<String, Long> stageLatencyMs, Integer promptTokens,
                       Integer completionTokens, String answer, String guardReason) {
        return record(requestId, ctx, projectIds, rawQuery, condensedQuery, backend, hits,
                stageLatencyMs, promptTokens, completionTokens, answer, guardReason, null, false);
    }

    /**
     * Same, plus what query understanding decided: the filter that was actually applied, and
     * whether it had to be dropped because it matched nothing.
     */
    public UUID record(UUID requestId, SearchContext ctx, List<Long> projectIds, String rawQuery,
                       String condensedQuery, String backend, List<SearchHit> hits,
                       Map<String, Long> stageLatencyMs, Integer promptTokens,
                       Integer completionTokens, String answer, String guardReason,
                       String appliedFilter, boolean filterWidened) {
        return record(requestId, ctx, projectIds, rawQuery, condensedQuery, backend, hits,
                stageLatencyMs, promptTokens, completionTokens, answer, guardReason, appliedFilter,
                filterWidened, null);
    }

    /** Same, plus which route answered - chitchat, aggregate, or search. */
    public UUID record(UUID requestId, SearchContext ctx, List<Long> projectIds, String rawQuery,
                       String condensedQuery, String backend, List<SearchHit> hits,
                       Map<String, Long> stageLatencyMs, Integer promptTokens,
                       Integer completionTokens, String answer, String guardReason,
                       String appliedFilter, boolean filterWidened, String route) {
        if (!props.isEnabled()) {
            return requestId;
        }
        try {
            RagTrace trace = new RagTrace(
                    requestId,
                    Instant.now(),
                    ctx.principal(),
                    projectIds,
                    rawQuery,
                    // Only interesting when it actually differs - a condensed query identical to the
                    // raw one is noise, and the difference is what breaks follow-up retrieval.
                    rawQuery.equals(condensedQuery) ? null : condensedQuery,
                    backend,
                    toRetrieved(hits),
                    stageLatencyMs,
                    promptTokens,
                    completionTokens,
                    truncate(answer),
                    guardReason,
                    appliedFilter,
                    filterWidened,
                    route);
            repo.insert(trace);
            repo.prune(ctx.principal(), props.getKeep());
        } catch (RuntimeException e) {
            log.warn("could not record trace {} - the answer was still delivered", requestId, e);
        }
        return requestId;
    }

    private static List<RagTrace.Retrieved> toRetrieved(List<SearchHit> hits) {
        List<RagTrace.Retrieved> out = new ArrayList<>();
        if (hits == null) return out;
        for (SearchHit h : hits) {
            out.add(new RagTrace.Retrieved(h.docId(), h.chunkIndex(), h.score()));
        }
        return out;
    }

    private String truncate(String answer) {
        if (answer == null) return null;
        int max = props.getMaxAnswerChars();
        return answer.length() <= max ? answer : answer.substring(0, max) + "…[truncated]";
    }
}
