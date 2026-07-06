package com.example.springbootrag.service;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.chat.ChatProvider.ChatMessage;
import com.example.springbootrag.config.ChatProperties;
import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.web.dto.AskResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Streaming, multi-turn RAG chat. Stateless: the client sends the conversation each turn.
 * Retrieval runs on the latest user message; the whole (trimmed) thread is fed to the model.
 */
@Service
public class ChatService {

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

    public ChatService(SearchService searchService, ChatProvider chat, ChatProperties props) {
        this.searchService = searchService;
        this.chat = chat;
        this.props = props;
    }

    /**
     * Retrieves for the latest question, streams the answer via {@code onToken}, and returns
     * the citation sources. Sources are known before generation, so the caller may emit them first.
     *
     * @param projectIds optional project scope (empty = all projects)
     * @param docIds optional document scope (empty = all documents)
     */
    /** Backward-compatible overload: no reasoning channel, thinking disabled. */
    public List<AskResponse.Source> chatStream(List<ChatMessage> history,
                                               List<Long> projectIds,
                                               List<String> docIds,
                                               Consumer<String> onToken) {
        return chatStream(history, projectIds, docIds, false, onToken, r -> {});
    }

    public List<AskResponse.Source> chatStream(List<ChatMessage> history,
                                               List<Long> projectIds,
                                               List<String> docIds,
                                               boolean think,
                                               Consumer<String> onToken,
                                               Consumer<String> onReasoning) {
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
        List<SearchHit> hits = searchService.search("rerank", retrievalQuery,
                props.getContextChunks(), pScope, dScope);
        if (hits.isEmpty()) {
            onToken.accept("No relevant chunks found in the knowledge base.");
            return List.of();
        }

        // Prior turns verbatim; the final user turn carries the numbered context + question.
        List<ChatMessage> modelMessages = new ArrayList<>(trimmed.subList(0, trimmed.size() - 1));
        modelMessages.add(new ChatMessage("user", AskService.buildUserPrompt(last.content(), hits)));

        chat.chatStream(AskService.SYSTEM_PROMPT, modelMessages, think, onToken, onReasoning);

        List<AskResponse.Source> sources = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            sources.add(new AskResponse.Source(i + 1, h.docId(), h.headingPath(), h.score(), h.content(), h.chunkIndex()));
        }
        return sources;
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
