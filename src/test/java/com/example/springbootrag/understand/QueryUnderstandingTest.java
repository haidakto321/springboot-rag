package com.example.springbootrag.understand;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.config.UnderstandProperties;
import com.example.springbootrag.repository.FacetRepository;
import com.example.springbootrag.security.TestContexts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryUnderstandingTest {

    private static final List<Facet> FACETS = List.of(
            new Facet("invoice", "values.customer", "text", List.of("ACME Corp"), 3),
            new Facet("invoice", "values.total", "number", List.of("1899.5"), 9));

    /** Returns whatever the test tells it to, and records the prompt it was given. */
    private static class StubChat implements ChatProvider {
        String lastSystem;
        String lastUser;
        Function<String, String> reply = q -> "{}";
        RuntimeException boom;

        @Override public String chat(String systemPrompt, String userPrompt) {
            lastSystem = systemPrompt;
            lastUser = userPrompt;
            if (boom != null) throw boom;
            return reply.apply(userPrompt);
        }
    }

    private QueryUnderstanding service(StubChat chat, boolean enabled) {
        FacetRepository repo = mock(FacetRepository.class);
        when(repo.facets(any(), anyList(), anyInt())).thenReturn(FACETS);
        UnderstandProperties props = new UnderstandProperties();
        props.setEnabled(enabled);
        var catalogue = new FacetCatalogue(repo, props);
        var chatProps = new com.example.springbootrag.config.ChatProperties();
        return new QueryUnderstanding(chat, catalogue, props, chatProps);
    }

    @Test
    void extractsAFilterFromTheModelReply() {
        StubChat chat = new StubChat();
        chat.reply = q -> """
                {"docType":"invoice",
                 "filters":[{"path":"values.customer","op":"eq","value":"ACME Corp"}]}""";

        var extraction = service(chat, true).extract(TestContexts.PUBLIC, List.of(1L),
                "unpaid ACME Corp invoices");

        assertThat(extraction.filter().docType()).isEqualTo("invoice");
        assertThat(extraction.filter().conditions()).hasSize(1);
    }

    @Test
    void thePromptCarriesTheFacetsAndTheQuestion() {
        StubChat chat = new StubChat();
        service(chat, true).extract(TestContexts.PUBLIC, List.of(1L), "invoices over 1000");

        assertThat(chat.lastSystem).contains("values.customer").contains("values.total")
                .contains("number");
        assertThat(chat.lastUser).contains("invoices over 1000");
    }

    @Test
    void disabledMeansNoModelCallAndNoFilter() {
        StubChat chat = new StubChat();
        var extraction = service(chat, false).extract(TestContexts.PUBLIC, List.of(1L), "anything");

        assertThat(extraction.filter().isEmpty()).isTrue();
        assertThat(chat.lastUser).isNull();
    }

    @Test
    void aModelFailureIsNotARequestFailure() {
        StubChat chat = new StubChat();
        chat.boom = new IllegalStateException("ollama down");

        var extraction = service(chat, true).extract(TestContexts.PUBLIC, List.of(1L), "anything");

        assertThat(extraction.filter().isEmpty()).isTrue();
        assertThat(extraction.dropped()).anyMatch(s -> s.contains("extraction failed"));
    }

    @Test
    void anEmptyCatalogueSkipsTheModelEntirely() {
        // Nothing to filter on means nothing to extract - do not pay for a model call.
        FacetRepository empty = mock(FacetRepository.class);
        when(empty.facets(any(), anyList(), anyInt())).thenReturn(List.of());
        UnderstandProperties props = new UnderstandProperties();
        StubChat chat = new StubChat();
        var service = new QueryUnderstanding(chat, new FacetCatalogue(empty, props), props,
                new com.example.springbootrag.config.ChatProperties());

        var extraction = service.extract(TestContexts.PUBLIC, List.of(1L), "anything");

        assertThat(extraction.filter().isEmpty()).isTrue();
        assertThat(chat.lastUser).isNull();
    }

    @Test
    void latencyIsRecorded() {
        StubChat chat = new StubChat();
        var extraction = service(chat, true).extract(TestContexts.PUBLIC, List.of(1L), "q");

        assertThat(extraction.latencyMs()).isGreaterThanOrEqualTo(0);
    }
}
