package com.example.springbootrag.service;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.chat.ChatProvider.ChatMessage;
import com.example.springbootrag.config.ChatProperties;
import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.repository.MetadataFilter;
import com.example.springbootrag.security.SearchContext;
import com.example.springbootrag.web.dto.AskResponse;
import com.example.springbootrag.security.TestContexts;
import com.example.springbootrag.trace.NoopTraceRecorder;
import com.example.springbootrag.understand.QueryUnderstanding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    private final SearchService searchService = mock(SearchService.class);

    /** Captures the assembled calls; chat() returns the canned condensed query, chatStream() emits tokens. */
    static class FakeChat implements ChatProvider {
        String lastSystem;
        List<ChatMessage> lastMessages;
        String condensedReturn = "condensed standalone query";
        String lastChatSystem;
        String lastChatUser;
        boolean throwOnChat = false;

        @Override public String chat(String s, String u) {
            this.lastChatSystem = s;
            this.lastChatUser = u;
            if (throwOnChat) throw new RuntimeException("chat down");
            return condensedReturn;
        }
        @Override public void chatStream(String system, List<ChatMessage> messages, Consumer<String> onToken) {
            this.lastSystem = system;
            this.lastMessages = messages;
            onToken.accept("Hello");
            onToken.accept(" there");
        }
    }

    private final FakeChat chat = new FakeChat();
    private final ChatProperties props = new ChatProperties();
    private final QueryUnderstanding understanding = mock(QueryUnderstanding.class);
    private final ChatService service =
            new ChatService(searchService, chat, props, NoopTraceRecorder.create(), understanding);

    @BeforeEach
    void extractionFindsNothingByDefault() {
        when(understanding.extract(any(), anyList(), anyString()))
                .thenReturn(QueryUnderstanding.Extraction.none());
    }

    @Test
    void followupRetrievesWithCondensedQueryButGeneratesFromOriginal() {
        // On a follow-up, retrieval uses the condensed query; generation keeps the original question.
        when(searchService.searchTraced(any(SearchContext.class), eq("rerank"), eq("condensed standalone query"), anyInt(), anyList(), any(), any(MetadataFilter.class))).thenReturn(new SearchService.TracedSearch(List.of(
                new SearchHit(1, "doc-a", 0, "chunk one text", "a.md", "# A > ## S", 0.9, null),
                new SearchHit(2, "doc-b", 3, "chunk two text", "b.md", null, 0.7, null)), Map.of("embed", 1L, "retrieve", 2L)));

        List<String> tokens = new ArrayList<>();
        List<AskResponse.Source> sources = service.chatStream(TestContexts.PUBLIC, List.of(
                new ChatMessage("user", "how does chunking work?"),
                new ChatMessage("assistant", "It splits on headings."),
                new ChatMessage("user", "what about overlap?")), List.of(), List.of(), tokens::add).sources();

        assertThat(tokens).containsExactly("Hello", " there");

        // Condensation was invoked with the prior conversation + follow-up.
        assertThat(chat.lastChatSystem).contains("standalone");
        assertThat(chat.lastChatUser).contains("Follow-up: what about overlap?");

        // Generation: system prompt forwarded; prior turns preserved; final user turn keeps the ORIGINAL question.
        assertThat(chat.lastSystem).contains("ONLY");
        assertThat(chat.lastMessages).hasSize(3);
        assertThat(chat.lastMessages.get(0).content()).isEqualTo("how does chunking work?");
        assertThat(chat.lastMessages.get(2).content())
                .contains("[1]").contains("chunk one text")
                .endsWith("Question: what about overlap?");

        assertThat(sources).hasSize(2);
        assertThat(sources.get(0).docId()).isEqualTo("doc-a");
    }

    @Test
    void firstTurnSkipsCondensation() {
        when(searchService.searchTraced(any(SearchContext.class), eq("rerank"), eq("how does chunking work?"), anyInt(), anyList(), any(), any(MetadataFilter.class))).thenReturn(new SearchService.TracedSearch(List.of(new SearchHit(1, "d", 0, "c", null, null, 0.5, null)), Map.of("embed", 1L, "retrieve", 2L)));

        service.chatStream(TestContexts.PUBLIC, List.of(new ChatMessage("user", "how does chunking work?")), List.of(), List.of(), t -> {});

        // No prior turns -> condensation (chat.chat) never called; retrieval uses the raw question.
        assertThat(chat.lastChatUser).isNull();
    }

    @Test
    void condensationFailureFallsBackToRawQuery() {
        chat.throwOnChat = true;
        when(searchService.searchTraced(any(SearchContext.class), eq("rerank"), eq("what about overlap?"), anyInt(), anyList(), any(), any(MetadataFilter.class))).thenReturn(new SearchService.TracedSearch(List.of(new SearchHit(1, "d", 0, "c", null, null, 0.5, null)), Map.of("embed", 1L, "retrieve", 2L)));

        List<String> tokens = new ArrayList<>();
        service.chatStream(TestContexts.PUBLIC, List.of(
                new ChatMessage("user", "how does chunking work?"),
                new ChatMessage("assistant", "It splits on headings."),
                new ChatMessage("user", "what about overlap?")), List.of(), List.of(), tokens::add);

        // Condensation threw -> retrieval used the raw follow-up, chat still answered.
        assertThat(tokens).containsExactly("Hello", " there");
    }

    @Test
    void trimsHistoryToLastTenMessages() {
        when(searchService.searchTraced(any(SearchContext.class), anyString(), anyString(), anyInt(), anyList(), any(), any(MetadataFilter.class))).thenReturn(new SearchService.TracedSearch(List.of(new SearchHit(1, "d", 0, "c", null, null, 0.5, null)), Map.of("embed", 1L, "retrieve", 2L)));

        // 12 messages; last is a user turn.
        List<ChatMessage> history = new ArrayList<>(IntStream.range(0, 11)
                .mapToObj(i -> new ChatMessage(i % 2 == 0 ? "user" : "assistant", "m" + i)).toList());
        history.add(new ChatMessage("user", "final question"));

        service.chatStream(TestContexts.PUBLIC, history, List.of(), List.of(), t -> {});

        // 10 forwarded: 9 prior + 1 context-bearing final user message.
        assertThat(chat.lastMessages).hasSize(10);
        assertThat(chat.lastMessages.get(chat.lastMessages.size() - 1).content())
                .endsWith("Question: final question");
    }

    @Test
    void noHitsEmitsFallbackAndSkipsModel() {
        when(searchService.searchTraced(any(SearchContext.class), anyString(), anyString(), anyInt(), anyList(), any(), any(MetadataFilter.class))).thenReturn(new SearchService.TracedSearch(List.of(), Map.of("embed", 1L, "retrieve", 2L)));

        List<String> tokens = new ArrayList<>();
        List<AskResponse.Source> sources =
                service.chatStream(TestContexts.PUBLIC, List.of(new ChatMessage("user", "anything?")), List.of(), List.of(), tokens::add).sources();

        assertThat(String.join("", tokens)).contains("No relevant chunks");
        assertThat(sources).isEmpty();
        assertThat(chat.lastMessages).isNull(); // model never called
    }

    @Test
    void forwardsDocIdScopeToRetrieval() {
        when(searchService.searchTraced(any(SearchContext.class), anyString(), anyString(), anyInt(), anyList(), any(), any(MetadataFilter.class))).thenReturn(new SearchService.TracedSearch(List.of(new SearchHit(1, "doc-a", 0, "c", null, null, 0.5, null)), Map.of("embed", 1L, "retrieve", 2L)));

        service.chatStream(TestContexts.PUBLIC, List.of(new ChatMessage("user", "q")), List.of(), List.of("doc-a", "doc-b"), t -> {});

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> scope = ArgumentCaptor.forClass(List.class);
        verify(searchService).searchTraced(any(SearchContext.class), eq("rerank"), eq("q"), anyInt(), anyList(), scope.capture(), any(MetadataFilter.class));
        assertThat(scope.getValue()).containsExactly("doc-a", "doc-b");
    }

    @Test
    void extractionUsesTheRawQuestionNotTheCondensedOne() {
        // Condensation rewrites pronouns but can drop the entity the filter needs.
        when(searchService.searchTraced(any(SearchContext.class), eq("rerank"),
                eq("condensed standalone query"), anyInt(), anyList(), any(), any(MetadataFilter.class)))
                .thenReturn(new SearchService.TracedSearch(
                        List.of(new SearchHit(1, "d", 0, "c", null, null, 0.5, null)),
                        Map.of("embed", 1L, "retrieve", 2L)));

        service.chatStream(TestContexts.PUBLIC, List.of(
                new ChatMessage("user", "what about ACME Corp?"),
                new ChatMessage("assistant", "..."),
                new ChatMessage("user", "and their unpaid ones?")), List.of(), List.of(), t -> {});

        verify(understanding).extract(any(), anyList(), eq("and their unpaid ones?"));
    }

    @Test
    void anExtractedFilterIsReportedBeforeTheFirstToken() {
        MetadataFilter extracted = MetadataFilter.parse("""
                {"filters":[{"path":"values.customer","op":"eq","value":"ACME Corp"}]}""");
        when(understanding.extract(any(), anyList(), anyString()))
                .thenReturn(new QueryUnderstanding.Extraction(extracted, 42L, List.of()));
        when(searchService.searchTraced(any(SearchContext.class), anyString(), anyString(), anyInt(),
                anyList(), any(), any(MetadataFilter.class)))
                .thenReturn(new SearchService.TracedSearch(
                        List.of(new SearchHit(1, "d", 0, "c", null, null, 0.5, null)),
                        Map.of("embed", 1L, "retrieve", 2L)));

        List<String> events = new ArrayList<>();
        ChatService.StreamOutcome outcome = service.chatStream(TestContexts.PUBLIC,
                List.of(new ChatMessage("user", "invoices for ACME Corp")), List.of(), List.of(),
                false, MetadataFilter.none(),
                f -> events.add("filter:" + f.get("widened")), t -> events.add("token"), r -> {});

        assertThat(events).first().isEqualTo("filter:false");
        assertThat(outcome.appliedFilter()).isNotNull();
        assertThat(outcome.widened()).isFalse();
    }

    @Test
    void aFilterThatMatchesNothingWidensAndSaysSo() {
        MetadataFilter extracted = MetadataFilter.parse("""
                {"filters":[{"path":"values.customer","op":"eq","value":"NOBODY Ltd"}]}""");
        when(understanding.extract(any(), anyList(), anyString()))
                .thenReturn(new QueryUnderstanding.Extraction(extracted, 42L, List.of()));
        // Filtered retrieval finds nothing; the unfiltered retry does.
        when(searchService.searchTraced(any(SearchContext.class), anyString(), anyString(), anyInt(),
                anyList(), any(), argThat(f -> f != null && !f.isEmpty())))
                .thenReturn(new SearchService.TracedSearch(List.of(), Map.of("retrieve", 2L)));
        when(searchService.searchTraced(any(SearchContext.class), anyString(), anyString(), anyInt(),
                anyList(), any(), argThat(f -> f == null || f.isEmpty())))
                .thenReturn(new SearchService.TracedSearch(
                        List.of(new SearchHit(1, "d", 0, "c", null, null, 0.5, null)),
                        Map.of("retrieve", 3L)));

        List<String> tokens = new ArrayList<>();
        ChatService.StreamOutcome outcome = service.chatStream(TestContexts.PUBLIC,
                List.of(new ChatMessage("user", "invoices for NOBODY")), List.of(), List.of(),
                tokens::add);

        assertThat(outcome.widened()).isTrue();
        assertThat(outcome.sources()).hasSize(1);
        assertThat(tokens).containsExactly("Hello", " there");
    }

    @Test
    void anExplicitCallerFilterSkipsExtraction() {
        when(searchService.searchTraced(any(SearchContext.class), anyString(), anyString(), anyInt(),
                anyList(), any(), any(MetadataFilter.class)))
                .thenReturn(new SearchService.TracedSearch(
                        List.of(new SearchHit(1, "d", 0, "c", null, null, 0.5, null)),
                        Map.of("retrieve", 2L)));

        service.chatStream(TestContexts.PUBLIC, List.of(new ChatMessage("user", "q")), List.of(),
                List.of(), false,
                MetadataFilter.parse("""
                        {"filters":[{"path":"values.customer","op":"eq","value":"GLOBEX Ltd"}]}"""),
                t -> {}, r -> {});

        verify(understanding, never()).extract(any(), anyList(), anyString());
    }

    @Test
    void emptyHistoryIsRejected() {
        assertThatThrownBy(() -> service.chatStream(TestContexts.PUBLIC, List.of(), List.of(), List.of(), t -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lastMessageMustBeUser() {
        assertThatThrownBy(() -> service.chatStream(TestContexts.PUBLIC, 
                List.of(new ChatMessage("assistant", "hi")), List.of(), List.of(), t -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oversizedHistoryIsRejected() {
        List<ChatMessage> huge = IntStream.range(0, 51)
                .mapToObj(i -> new ChatMessage("user", "m" + i)).toList();
        assertThatThrownBy(() -> service.chatStream(TestContexts.PUBLIC, huge, List.of(), List.of(), t -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
