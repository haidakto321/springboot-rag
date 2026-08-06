package com.example.springbootrag.service;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.chat.ChatProvider.ChatMessage;
import com.example.springbootrag.config.ChatProperties;
import com.example.springbootrag.guard.AnswerGuard;
import com.example.springbootrag.model.SearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.springbootrag.security.SearchContext;
import com.example.springbootrag.trace.TraceRecorder;
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

    public ChatService(SearchService searchService, ChatProvider chat, ChatProperties props,
                       TraceRecorder tracer) {
        this.searchService = searchService;
        this.chat = chat;
        this.props = props;
        this.tracer = tracer;
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
     * <p>A streamed token cannot be recalled, so unlike {@code AskService} the chat path cannot
     * replace a bad answer with a refusal - it can only tell the client that what it just rendered
     * failed the check. That is a real limitation of streaming, not an oversight: buffering the
     * whole answer to guard it first would trade away the reason streaming exists.
     */
    public record StreamOutcome(List<AskResponse.Source> sources, AnswerGuard.Verdict verdict,
                                java.util.UUID requestId) {}

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
                                    com.example.springbootrag.repository.MetadataFilter filter,
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

        // On follow-up turns, retrieve using a standalone query condensed from the conversation;
        // the first turn's question is already standalone.
        String retrievalQuery = last.content();
        List<ChatMessage> prior = trimmed.subList(0, trimmed.size() - 1);
        if (props.isCondenseFollowups() && !prior.isEmpty()) {
            retrievalQuery = condenseQuery(prior, last.content());
        }

        List<Long> pScope = projectIds == null ? List.of() : projectIds;
        List<String> dScope = docIds == null ? List.of() : docIds;
        // Retrieval runs on the condensed query; the filter is the caller's and applies unchanged.
        SearchService.TracedSearch search = searchService.searchTraced(ctx, "rerank", retrievalQuery,
                props.getContextChunks(), pScope, dScope, filter);
        List<SearchHit> hits = search.hits();
        Map<String, Long> stages = new LinkedHashMap<>(search.stageLatencyMs());
        if (hits.isEmpty()) {
            onToken.accept("No relevant chunks found in the knowledge base.");
            stages.put("total", msSince(start));
            tracer.record(requestId, ctx, pScope, last.content(), retrievalQuery, "rerank", hits,
                    stages, null, null, null, "no-hits");
            return new StreamOutcome(List.of(),
                    new AnswerGuard.Verdict(true, "no-hits", AnswerGuard.REFUSAL), requestId);
        }

        // Prior turns verbatim; the final user turn carries the fenced context + question.
        List<ChatMessage> modelMessages = new ArrayList<>(trimmed.subList(0, trimmed.size() - 1));
        modelMessages.add(new ChatMessage("user", AskService.buildUserPrompt(last.content(), hits)));

        // Tee the stream: the client gets tokens live, and a copy is kept so the finished answer
        // can still be checked for grounding and written to the trace.
        StringBuilder full = new StringBuilder();
        java.util.concurrent.atomic.AtomicReference<ChatProvider.Usage> usage =
                new java.util.concurrent.atomic.AtomicReference<>(ChatProvider.Usage.unknown());
        long beforeGenerate = System.nanoTime();
        chat.chatStream(AskService.SYSTEM_PROMPT, modelMessages, think,
                token -> { full.append(token); onToken.accept(token); }, onReasoning, usage::set);
        stages.put("generate", (System.nanoTime() - beforeGenerate) / 1_000_000);

        AnswerGuard.Verdict verdict = AnswerGuard.check(full.toString(), hits.size());
        if (!verdict.allowed()) {
            log.warn("streamed answer failed the grounding guard ({}) - already sent to the client",
                    verdict.reason());
        }
        stages.put("total", msSince(start));
        tracer.record(requestId, ctx, pScope, last.content(), retrievalQuery, "rerank", hits, stages,
                usage.get().promptTokens(), usage.get().completionTokens(),
                full.toString(), verdict.reason());

        List<AskResponse.Source> sources = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            sources.add(new AskResponse.Source(i + 1, h.docId(), h.headingPath(), h.score(), h.content(), h.chunkIndex()));
        }
        return new StreamOutcome(sources, verdict, requestId);
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
