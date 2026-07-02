package com.example.springbootrag.service;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.chat.ChatProvider.ChatMessage;
import com.example.springbootrag.config.ChatProperties;
import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.web.dto.AskResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    private final SearchService searchService = mock(SearchService.class);

    /** Captures the assembled call and emits two canned tokens. */
    static class FakeChat implements ChatProvider {
        String lastSystem;
        List<ChatMessage> lastMessages;
        @Override public String chat(String s, String u) { return "unused"; }
        @Override public void chatStream(String system, List<ChatMessage> messages, Consumer<String> onToken) {
            this.lastSystem = system;
            this.lastMessages = messages;
            onToken.accept("Hello");
            onToken.accept(" there");
        }
    }

    private final FakeChat chat = new FakeChat();
    private final ChatProperties props = new ChatProperties();
    private final ChatService service = new ChatService(searchService, chat, props);

    @Test
    void retrievesOnLastMessageStreamsTokensAndReturnsSources() {
        when(searchService.search(eq("rerank"), eq("what about overlap?"), anyInt(), any())).thenReturn(List.of(
                new SearchHit(1, "doc-a", 0, "chunk one text", "a.md", "# A > ## S", 0.9),
                new SearchHit(2, "doc-b", 3, "chunk two text", "b.md", null, 0.7)));

        List<String> tokens = new ArrayList<>();
        List<AskResponse.Source> sources = service.chatStream(List.of(
                new ChatMessage("user", "how does chunking work?"),
                new ChatMessage("assistant", "It splits on headings."),
                new ChatMessage("user", "what about overlap?")), List.of(), tokens::add);

        assertThat(tokens).containsExactly("Hello", " there");

        // System prompt forwarded; prior turns preserved; final user turn carries the context.
        assertThat(chat.lastSystem).contains("ONLY");
        assertThat(chat.lastMessages).hasSize(3);
        assertThat(chat.lastMessages.get(0).content()).isEqualTo("how does chunking work?");
        assertThat(chat.lastMessages.get(1).content()).isEqualTo("It splits on headings.");
        assertThat(chat.lastMessages.get(2).content())
                .contains("[1]").contains("chunk one text")
                .endsWith("Question: what about overlap?");

        assertThat(sources).hasSize(2);
        assertThat(sources.get(0).docId()).isEqualTo("doc-a");
        assertThat(sources.get(1).index()).isEqualTo(2);
    }

    @Test
    void trimsHistoryToLastTenMessages() {
        when(searchService.search(anyString(), anyString(), anyInt(), any()))
                .thenReturn(List.of(new SearchHit(1, "d", 0, "c", null, null, 0.5)));

        // 12 messages; last is a user turn.
        List<ChatMessage> history = new ArrayList<>(IntStream.range(0, 11)
                .mapToObj(i -> new ChatMessage(i % 2 == 0 ? "user" : "assistant", "m" + i)).toList());
        history.add(new ChatMessage("user", "final question"));

        service.chatStream(history, List.of(), t -> {});

        // 10 forwarded: 9 prior + 1 context-bearing final user message.
        assertThat(chat.lastMessages).hasSize(10);
        assertThat(chat.lastMessages.get(chat.lastMessages.size() - 1).content())
                .endsWith("Question: final question");
    }

    @Test
    void noHitsEmitsFallbackAndSkipsModel() {
        when(searchService.search(anyString(), anyString(), anyInt(), any())).thenReturn(List.of());

        List<String> tokens = new ArrayList<>();
        List<AskResponse.Source> sources =
                service.chatStream(List.of(new ChatMessage("user", "anything?")), List.of(), tokens::add);

        assertThat(String.join("", tokens)).contains("No relevant chunks");
        assertThat(sources).isEmpty();
        assertThat(chat.lastMessages).isNull(); // model never called
    }

    @Test
    void forwardsDocIdScopeToRetrieval() {
        when(searchService.search(anyString(), anyString(), anyInt(), any()))
                .thenReturn(List.of(new SearchHit(1, "doc-a", 0, "c", null, null, 0.5)));

        service.chatStream(List.of(new ChatMessage("user", "q")), List.of("doc-a", "doc-b"), t -> {});

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> scope = ArgumentCaptor.forClass(List.class);
        verify(searchService).search(eq("rerank"), eq("q"), anyInt(), scope.capture());
        assertThat(scope.getValue()).containsExactly("doc-a", "doc-b");
    }

    @Test
    void emptyHistoryIsRejected() {
        assertThatThrownBy(() -> service.chatStream(List.of(), List.of(), t -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lastMessageMustBeUser() {
        assertThatThrownBy(() -> service.chatStream(
                List.of(new ChatMessage("assistant", "hi")), List.of(), t -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oversizedHistoryIsRejected() {
        List<ChatMessage> huge = IntStream.range(0, 51)
                .mapToObj(i -> new ChatMessage("user", "m" + i)).toList();
        assertThatThrownBy(() -> service.chatStream(huge, List.of(), t -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
