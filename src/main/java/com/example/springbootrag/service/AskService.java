package com.example.springbootrag.service;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.config.ChatProperties;
import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.web.dto.AskResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Full RAG loop: retrieve (hybrid + rerank) then generate an answer from the chunks. */
@Service
public class AskService {

    static final String SYSTEM_PROMPT = """
            You are a knowledge-base assistant. Answer the question using ONLY the numbered \
            context chunks provided. Cite the chunks you used with their numbers in square \
            brackets, like [1] or [2]. If the context does not contain the answer, reply \
            exactly: Not found in knowledge base.""";

    private final SearchService searchService;
    private final ChatProvider chat;
    private final ChatProperties props;

    public AskService(SearchService searchService, ChatProvider chat, ChatProperties props) {
        this.searchService = searchService;
        this.chat = chat;
        this.props = props;
    }

    public AskResponse ask(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question is required");
        }
        // "rerank" = hybrid + reranker; with no reranker configured it degrades to plain hybrid.
        List<SearchHit> hits = searchService.search("rerank", question, props.getContextChunks());
        if (hits.isEmpty()) {
            return new AskResponse("No relevant chunks found in the knowledge base.", List.of());
        }
        String answer = chat.chat(SYSTEM_PROMPT, buildUserPrompt(question, hits));
        List<AskResponse.Source> sources = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            sources.add(new AskResponse.Source(i + 1, h.docId(), h.headingPath(), h.score(), h.content()));
        }
        return new AskResponse(answer, sources);
    }

    private static String buildUserPrompt(String question, List<SearchHit> hits) {
        StringBuilder sb = new StringBuilder("Context:\n");
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            sb.append('[').append(i + 1).append("] (").append(h.docId());
            if (h.headingPath() != null) {
                sb.append(" - ").append(h.headingPath());
            }
            sb.append(")\n").append(h.content()).append("\n\n");
        }
        sb.append("Question: ").append(question);
        return sb.toString();
    }
}
