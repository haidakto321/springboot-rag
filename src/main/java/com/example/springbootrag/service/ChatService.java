package com.example.springbootrag.service;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.chat.ChatProvider.ChatMessage;
import com.example.springbootrag.config.ChatProperties;
import com.example.springbootrag.guard.AnswerGuard;
import com.example.springbootrag.guard.GroundednessJudge;
import com.example.springbootrag.guard.GuardedEmitter;
import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.repository.MetadataFilter;
import com.example.springbootrag.repository.RecordCountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.springbootrag.security.SearchContext;
import com.example.springbootrag.trace.TraceRecorder;
import com.example.springbootrag.understand.FilterJson;
import com.example.springbootrag.understand.QueryRouter;
import com.example.springbootrag.understand.QueryUnderstanding;
import com.example.springbootrag.understand.Route;
import com.example.springbootrag.web.dto.AskResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Streaming, multi-turn RAG chat. Stateless: the client sends the conversation each turn.
 * Retrieval runs on the latest user message; the whole (trimmed) thread is fed to the model.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** Max conversation turns forwarded to the model, newest kept. Guards context + cost. */
    static final int MAX_HISTORY = 10;
    /** Hard reject threshold before trimming - defends against oversized request bodies. */
    static final int MAX_INCOMING = 50;

    static final String CONDENSE_SYSTEM = """
            Given a conversation and a follow-up question, rewrite the follow-up as a single \
            standalone search query that captures what to look for, using context from the \
            conversation. Output ONLY the rewritten query text, with no preamble or quotes.""";

    private final SearchService searchService;
    private final ChatProvider chat;
    private final ChatProperties props;
    private final TraceRecorder tracer;
    private final QueryUnderstanding understanding;
    private final QueryRouter router;
    private final RecordCountRepository counts;
    private final GroundednessJudge judge;

    public ChatService(SearchService searchService, ChatProvider chat, ChatProperties props,
                       TraceRecorder tracer, QueryUnderstanding understanding,
                       QueryRouter router, RecordCountRepository counts,
                       GroundednessJudge judge) {
        this.searchService = searchService;
        this.chat = chat;
        this.props = props;
        this.tracer = tracer;
        this.understanding = understanding;
        this.router = router;
        this.counts = counts;
        this.judge = judge;
    }

    /**
     * Retrieves for the latest question, streams the answer via {@code onToken}, and returns
     * the citation sources. Sources are known before generation, so the caller may emit them first.
     *
     * @param projectIds optional project scope (empty = all projects)
     * @param docIds optional document scope (empty = all documents)
     */
    /**
     * What the stream produced: the citations, plus the grounding verdict for the text that was
     * already sent.
     *
     * <p>A streamed token cannot be recalled, so what this path can retract depends on when the
     * failure becomes decidable. {@link GuardedEmitter} holds tokens until the answer cites a
     * supplied chunk, so "no citation at all" and "a fabricated first citation" are caught before
     * anything is sent and the answer is replaced by a refusal. A citation that goes out of range
     * mid-answer stops the stream, leaving the already-sent prefix. A groundedness failure needs
     * the whole claim and can only be reported.
     */
    public record StreamOutcome(List<AskResponse.Source> sources, AnswerGuard.Verdict verdict,
                                java.util.UUID requestId, Object appliedFilter, boolean widened,
                                String route) {

        /** A turn that did no filtering. */
        public StreamOutcome(List<AskResponse.Source> sources, AnswerGuard.Verdict verdict,
                             java.util.UUID requestId) {
            this(sources, verdict, requestId, null, false, "search");
        }

        /** Pre-routing callers: the search route, which is the only one they ever took. */
        public StreamOutcome(List<AskResponse.Source> sources, AnswerGuard.Verdict verdict,
                             java.util.UUID requestId, Object appliedFilter, boolean widened) {
            this(sources, verdict, requestId, appliedFilter, widened, "search");
        }
    }

    /** Convenience overload: no reasoning channel, thinking disabled. */
    public StreamOutcome chatStream(SearchContext ctx,
                                    List<ChatMessage> history,
                                    List<Long> projectIds,
                                    List<String> docIds,
                                    Consumer<String> onToken) {
        return chatStream(ctx, history, projectIds, docIds, false, onToken, r -> {});
    }

    public StreamOutcome chatStream(SearchContext ctx,
                                    List<ChatMessage> history,
                                    List<Long> projectIds,
                                    List<String> docIds,
                                    boolean think,
                                    Consumer<String> onToken,
                                    Consumer<String> onReasoning) {
        return chatStream(ctx, history, projectIds, docIds, think,
                com.example.springbootrag.repository.MetadataFilter.none(), onToken, onReasoning);
    }

    /** Same, narrowed by structured record metadata. */
    public StreamOutcome chatStream(SearchContext ctx,
                                    List<ChatMessage> history,
                                    List<Long> projectIds,
                                    List<String> docIds,
                                    boolean think,
                                    MetadataFilter filter,
                                    Consumer<String> onToken,
                                    Consumer<String> onReasoning) {
        return chatStream(ctx, history, projectIds, docIds, think, filter, f -> {}, onToken,
                onReasoning);
    }

    /**
     * Same, reporting the filter query understanding decided on.
     *
     * <p>{@code onFilter} exists because that decision has to reach the client BEFORE the tokens do:
     * once the answer is streaming, "by the way, I narrowed your search" arrives too late to change
     * how the reader reads it. It receives {@code applied} (the filter in API shape, absent when
     * there was none) and {@code widened}.
     */
    public StreamOutcome chatStream(SearchContext ctx,
                                    List<ChatMessage> history,
                                    List<Long> projectIds,
                                    List<String> docIds,
                                    boolean think,
                                    MetadataFilter filter,
                                    Consumer<Map<String, Object>> onFilter,
                                    Consumer<String> onToken,
                                    Consumer<String> onReasoning) {
        return chatStream(ctx, history, projectIds, docIds, think, filter, r -> {}, onFilter,
                onToken, onReasoning);
    }

    /**
     * Same, reporting which route is answering.
     *
     * <p>{@code onRoute} fires before everything else, because an answer with no citations is
     * normal on the chit-chat and aggregate routes and alarming on the search one. The client has
     * to know which it is looking at while the answer arrives, not afterwards.
     */
    public StreamOutcome chatStream(SearchContext ctx,
                                    List<ChatMessage> history,
                                    List<Long> projectIds,
                                    List<String> docIds,
                                    boolean think,
                                    MetadataFilter filter,
                                    Consumer<String> onRoute,
                                    Consumer<Map<String, Object>> onFilter,
                                    Consumer<String> onToken,
                                    Consumer<String> onReasoning) {
        return chatStream(ctx, history, projectIds, docIds, think, filter, onRoute, onFilter,
                () -> {}, onToken, onReasoning);
    }

    /**
     * Same, signalling that tokens are being held back.
     *
     * <p>{@code onVerifying} fires once, immediately before generation, because the guard now runs
     * in front of the client rather than behind it: nothing is shown until the answer cites a
     * source. A blank pane with no explanation is indistinguishable from a hang.
     */
    public StreamOutcome chatStream(SearchContext ctx,
                                    List<ChatMessage> history,
                                    List<Long> projectIds,
                                    List<String> docIds,
                                    boolean think,
                                    MetadataFilter filter,
                                    Consumer<String> onRoute,
                                    Consumer<Map<String, Object>> onFilter,
                                    Runnable onVerifying,
                                    Consumer<String> onToken,
                                    Consumer<String> onReasoning) {
        java.util.UUID requestId = java.util.UUID.randomUUID();
        long start = System.nanoTime();
        if (history == null || history.isEmpty()) {
            throw new IllegalArgumentException("messages are required");
        }
        if (history.size() > MAX_INCOMING) {
            throw new IllegalArgumentException("too many messages (max " + MAX_INCOMING + ")");
        }
        List<ChatMessage> trimmed = trimToLast(history, MAX_HISTORY);
        ChatMessage last = trimmed.get(trimmed.size() - 1);
        if (!"user".equals(last.role()) || last.content() == null || last.content().isBlank()) {
            throw new IllegalArgumentException("last message must be a non-empty user turn");
        }

        List<Long> pScope = projectIds == null ? List.of() : projectIds;
        List<String> dScope = docIds == null ? List.of() : docIds;

        // Route before condensing: condensation is itself an LLM call, and a greeting should not
        // pay for one. An explicit caller filter is already a structured request and stays on the
        // search path.
        boolean callerSuppliedFilter = filter != null && !filter.isEmpty();
        QueryRouter.Decision decision = callerSuppliedFilter
                ? QueryRouter.Decision.rule(Route.SEARCH)
                : router.route(last.content());
        String route = decision.route().label();
        onRoute.accept(route);

        if (decision.route() == Route.CHITCHAT) {
            onToken.accept(AggregateAnswerer.CHITCHAT_REPLY);
            Map<String, Long> chitStages = new LinkedHashMap<>();
            chitStages.put("route", decision.latencyMs());
            chitStages.put("total", msSince(start));
            tracer.record(requestId, ctx, pScope, last.content(), null, "none", List.of(),
                    chitStages, null, null, AggregateAnswerer.CHITCHAT_REPLY, null, null, false,
                    route);
            return new StreamOutcome(List.of(),
                    new AnswerGuard.Verdict(true, "chitchat", AggregateAnswerer.CHITCHAT_REPLY),
                    requestId, null, false, route);
        }

        // On follow-up turns, retrieve using a standalone query condensed from the conversation;
        // the first turn's question is already standalone.
        String retrievalQuery = last.content();
        List<ChatMessage> prior = trimmed.subList(0, trimmed.size() - 1);
        if (props.isCondenseFollowups() && !prior.isEmpty()) {
            retrievalQuery = condenseQuery(prior, last.content());
        }

        // Extraction reads the RAW question; condensation is for retrieval wording, and it can
        // drop the entity the filter needs.
        QueryUnderstanding.Extraction extraction = callerSuppliedFilter
                ? QueryUnderstanding.Extraction.none()
                : understanding.extract(ctx, pScope, last.content());
        MetadataFilter effective = callerSuppliedFilter ? filter : extraction.filter();

        if (decision.route() == Route.AGGREGATE) {
            try {
                // No widening: zero is a correct count, and retrying unfiltered would answer a
                // different question than the one that was asked.
                long n = counts.count(ctx, pScope, effective);
                String countAnswer = AggregateAnswerer.answer(n, effective);
                Object countFilter = FilterJson.toApiShape(effective);
                if (countFilter != null) {
                    Map<String, Object> frame = new LinkedHashMap<>();
                    frame.put("applied", countFilter);
                    frame.put("widened", false);
                    onFilter.accept(frame);
                }
                onToken.accept(countAnswer);
                Map<String, Long> countStages = new LinkedHashMap<>();
                countStages.put("route", decision.latencyMs());
                countStages.put("understand", extraction.latencyMs());
                countStages.put("total", msSince(start));
                tracer.record(requestId, ctx, pScope, last.content(), null, "count", List.of(),
                        countStages, null, null, countAnswer, null,
                        FilterJson.toApiString(effective), false, route);
                return new StreamOutcome(List.of(),
                        new AnswerGuard.Verdict(true, "count", countAnswer), requestId,
                        countFilter, false, route);
            } catch (RuntimeException e) {
                // A broken count must not lose the answer: fall through to normal retrieval.
                log.warn("count failed; falling back to the search path", e);
                route = Route.SEARCH.label();
            }
        }

        // Retrieval runs on the condensed query, narrowed by whichever filter won.
        SearchService.TracedSearch search = searchService.searchTraced(ctx, "rerank", retrievalQuery,
                props.getContextChunks(), pScope, dScope, effective);
        boolean widened = false;
        if (search.hits().isEmpty() && !effective.isEmpty()) {
            // A wrong filter must cost one extra query, not a confident refusal.
            search = searchService.searchTraced(ctx, "rerank", retrievalQuery,
                    props.getContextChunks(), pScope, dScope, MetadataFilter.none());
            widened = true;
        }
        List<SearchHit> hits = search.hits();
        Map<String, Long> stages = new LinkedHashMap<>(search.stageLatencyMs());
        stages.put("route", decision.latencyMs());
        if (!callerSuppliedFilter) {
            stages.put("understand", extraction.latencyMs());
        }
        Object appliedFilter = FilterJson.toApiShape(effective);
        String filterJson = FilterJson.toApiString(effective);
        // Before any token: the reader must know the search was narrowed while reading the answer.
        if (appliedFilter != null || widened) {
            Map<String, Object> frame = new LinkedHashMap<>();
            if (appliedFilter != null) frame.put("applied", appliedFilter);
            frame.put("widened", widened);
            onFilter.accept(frame);
        }
        if (hits.isEmpty()) {
            onToken.accept("No relevant chunks found in the knowledge base.");
            stages.put("total", msSince(start));
            tracer.record(requestId, ctx, pScope, last.content(), retrievalQuery, "rerank", hits,
                    stages, null, null, null, "no-hits", filterJson, widened, route);
            return new StreamOutcome(List.of(),
                    new AnswerGuard.Verdict(true, "no-hits", AnswerGuard.REFUSAL), requestId,
                    appliedFilter, widened, route);
        }

        // Prior turns verbatim; the final user turn carries the fenced context + question.
        List<ChatMessage> modelMessages = new ArrayList<>(trimmed.subList(0, trimmed.size() - 1));
        modelMessages.add(new ChatMessage("user", AskService.buildUserPrompt(last.content(), hits)));

        // The guard runs in FRONT of the client, not behind it: tokens are held until the answer
        // cites a supplied chunk, so an ungrounded answer is never sent at all. A copy of the raw
        // stream is still kept, because the trace has to record what the MODEL said.
        onVerifying.run();
        GuardedEmitter emitter = new GuardedEmitter(hits.size(), onToken);
        StringBuilder full = new StringBuilder();
        java.util.concurrent.atomic.AtomicReference<ChatProvider.Usage> usage =
                new java.util.concurrent.atomic.AtomicReference<>(ChatProvider.Usage.unknown());
        long beforeGenerate = System.nanoTime();
        chat.chatStream(AskService.SYSTEM_PROMPT, modelMessages, think,
                token -> { full.append(token); emitter.accept(token); }, onReasoning, usage::set);
        stages.put("generate", (System.nanoTime() - beforeGenerate) / 1_000_000);

        AnswerGuard.Verdict verdict = emitter.finish();
        // The judge needs the whole claim, so on this path it can flag but not retract - the
        // emitter's retraction covers citation validity, which is what is decidable mid-stream.
        if (verdict.allowed() && !"refusal".equals(verdict.reason())) {
            GroundednessJudge.Result g = judge.judge(full.toString(), hits);
            if (g.latencyMs() > 0) {
                stages.put("ground", g.latencyMs());
            }
            if (!g.supported()) {
                // REFUSAL, not the model's text: the trace keeps the original separately, and if
                // nothing had reached the wire this verdict is what the client would be sent.
                verdict = new AnswerGuard.Verdict(false, "unsupported", AnswerGuard.REFUSAL);
            }
        }
        if (!verdict.allowed()) {
            if (emitter.sentAnything()) {
                log.warn("streamed answer failed the grounding guard ({}) after {} characters were "
                        + "already sent", verdict.reason(), full.length());
            } else {
                // Nothing reached the wire, so the refusal replaces the answer outright.
                onToken.accept(verdict.answer());
            }
        }
        stages.put("total", msSince(start));
        tracer.record(requestId, ctx, pScope, last.content(), retrievalQuery, "rerank", hits, stages,
                usage.get().promptTokens(), usage.get().completionTokens(),
                full.toString(), verdict.reason(), filterJson, widened, route);

        List<AskResponse.Source> sources = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            sources.add(new AskResponse.Source(i + 1, h.docId(), h.headingPath(), h.score(), h.content(), h.chunkIndex()));
        }
        return new StreamOutcome(sources, verdict, requestId, appliedFilter, widened, route);
    }

    private static long msSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * Rewrites a follow-up into a standalone search query using the prior conversation.
     * Best-effort: any failure or empty result falls back to the raw question.
     */
    private String condenseQuery(List<ChatMessage> prior, String question) {
        StringBuilder sb = new StringBuilder("Conversation:\n");
        for (ChatMessage m : prior) {
            sb.append(m.role()).append(": ").append(m.content()).append('\n');
        }
        sb.append("Follow-up: ").append(question);
        try {
            String rewritten = chat.chat(CONDENSE_SYSTEM, sb.toString());
            if (rewritten != null && !rewritten.strip().isBlank()) {
                return rewritten.strip();
            }
        } catch (RuntimeException e) {
            // condensation is best-effort; retrieval falls back to the raw question
        }
        return question;
    }

    private static List<ChatMessage> trimToLast(List<ChatMessage> messages, int n) {
        return messages.size() <= n ? messages : messages.subList(messages.size() - n, messages.size());
    }
}
