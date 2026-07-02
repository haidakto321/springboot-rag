package com.example.springbootrag.service;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.config.ChatProperties;
import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.web.dto.AskResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class AskServiceTest {

    private final SearchService searchService = mock(SearchService.class);

    /** Captures prompts and returns a canned answer. */
    static class FakeChat implements ChatProvider {
        String lastSystem;
        String lastUser;
        @Override public String chat(String systemPrompt, String userPrompt) {
            this.lastSystem = systemPrompt;
            this.lastUser = userPrompt;
            return "canned answer [1]";
        }
    }

    private final FakeChat chat = new FakeChat();
    private final ChatProperties props = new ChatProperties();
    private final AskService askService = new AskService(searchService, chat, props);

    @Test
    void buildsNumberedContextAndReturnsSources() {
        when(searchService.search(eq("rerank"), anyString(), anyInt())).thenReturn(List.of(
                new SearchHit(1, "doc-a", 0, "chunk one text", "a.md", "# A > ## S", 0.9),
                new SearchHit(2, "doc-b", 3, "chunk two text", "b.md", null, 0.7)));

        AskResponse resp = askService.ask("what happened?");

        assertThat(resp.answer()).isEqualTo("canned answer [1]");
        assertThat(chat.lastUser).contains("[1]").contains("chunk one text");
        assertThat(chat.lastUser).contains("[2]").contains("chunk two text");
        assertThat(chat.lastUser).contains("# A > ## S");
        assertThat(chat.lastUser).endsWith("Question: what happened?");
        assertThat(chat.lastSystem).contains("ONLY");

        assertThat(resp.sources()).hasSize(2);
        assertThat(resp.sources().get(0).index()).isEqualTo(1);
        assertThat(resp.sources().get(0).docId()).isEqualTo("doc-a");
        assertThat(resp.sources().get(0).headingPath()).isEqualTo("# A > ## S");
        assertThat(resp.sources().get(1).index()).isEqualTo(2);
    }

    @Test
    void emptyRetrievalShortCircuitsWithoutCallingLlm(){
        when(searchService.search(eq("rerank"), anyString(), anyInt())).thenReturn(List.of());
        ChatProvider mockChat = mock(ChatProvider.class);
        AskService svc = new AskService(searchService, mockChat, props);

        AskResponse resp = svc.ask("anything?");

        assertThat(resp.answer()).contains("No relevant chunks");
        assertThat(resp.sources()).isEmpty();
        verifyNoInteractions(mockChat);
    }

    @Test
    void blankQuestionIsRejected() {
        assertThatThrownBy(() -> askService.ask("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
