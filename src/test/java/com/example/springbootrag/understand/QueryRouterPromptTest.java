package com.example.springbootrag.understand;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The prompt is the interface to the model, so it is tested like one. A layout change that reads
 * as harmless is exactly how condition recall once fell from 0.73 to 0.07 with every unit test
 * still green.
 */
class QueryRouterPromptTest {

    @Test
    void everyRouteNameAppearsExactlyAsTheParserExpectsIt() {
        String prompt = QueryRouter.buildPrompt();

        for (Route route : Route.values()) {
            String label = route.name().toLowerCase(Locale.ROOT);
            assertThat(prompt).as("route %s must be named in the prompt", label).contains(label);
            assertThat(Route.parse(label)).isEqualTo(route);
        }
    }

    @Test
    void theAmbiguousCountingExampleIsPresent() {
        // Without this example the model reads "how many" as a counting keyword and misroutes a
        // payment-terms question into an aggregate, which answers a content question with a number.
        assertThat(QueryRouter.buildPrompt())
                .contains("how many days do I have to pay an invoice\" -> search");
    }

    @Test
    void aBarePhraseNamingDocumentsIsClaimedForSearch() {
        // Measured miss: "delivery notes shipped by Speedy Freight" names documents but asks
        // nothing, and the model called it small talk - answering a real query with a canned hello.
        String prompt = QueryRouter.buildPrompt();

        assertThat(prompt).contains("phrase naming documents or their contents");
        assertThat(prompt).contains("raised by Initech\" -> search");
    }

    @Test
    void aValueQuestionIsClaimedForSearchEvenWhenItSaysTotal() {
        // Measured live: "what is the total on invoice INV-5575" went to the aggregate route and
        // answered a factual question with a record count.
        String prompt = QueryRouter.buildPrompt();

        assertThat(prompt).contains("VALUE inside a");
        assertThat(prompt).contains("amount due on invoice INV-1234\" -> search");
    }

    @Test
    void noExampleReusesAGoldenQuestionVerbatim() {
        // An example copied from the eval set turns the eval into a memory test. The noun-phrase
        // example is deliberately phrased differently from the golden question that motivated it.
        assertThat(QueryRouter.buildPrompt())
                .doesNotContain("delivery notes shipped by Speedy Freight")
                .doesNotContain("how many invoices for ACME Corp")
                .doesNotContain("how many contracts with Initech");
    }

    @Test
    void thePromptAsksForOneWordOnly() {
        assertThat(QueryRouter.buildPrompt()).contains("one word");
    }

    @Test
    void thePromptStaysShortEnoughToPayForOnEveryQuestion() {
        // A router prompt is paid on every single question; a facet-sized prompt here would undo
        // the point of routing.
        assertThat(QueryRouter.buildPrompt().length()).isLessThan(1200);
    }
}
