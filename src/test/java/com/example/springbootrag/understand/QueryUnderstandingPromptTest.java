package com.example.springbootrag.understand;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the prompt LAYOUT, which turned out to be load-bearing.
 *
 * <p>The first version listed each facet as {@code "- invoice | values.customer | text | ..."} and
 * qwen3:4b answered with {@code "path": "invoice | values.customer"} - it copied the whole row,
 * because nothing named the columns. Every condition was then dropped as an unknown path and the
 * eval measured a condition recall of 0.07. None of the validator's unit tests could see it: they
 * feed hand-written JSON that already has the right path.
 */
class QueryUnderstandingPromptTest {

    private static final List<Facet> FACETS = List.of(
            new Facet("invoice", "values.customer", "text", List.of("ACME Corp", "GLOBEX Ltd"), 5),
            new Facet("invoice", "values.total", "number", List.of("1899.5"), 90),
            new Facet("delivery-note", "values.carrier", "text", List.of("NordCargo"), 3));

    @Test
    void everyPathIsLabelledAsAPath() {
        String prompt = QueryUnderstanding.buildPrompt(FACETS);

        assertThat(prompt).contains("path: values.customer")
                .contains("path: values.total")
                .contains("path: values.carrier");
    }

    @Test
    void noLineCarriesBothTheDocTypeAndThePath() {
        // The exact shape that made the model concatenate them.
        for (String line : QueryUnderstanding.buildPrompt(FACETS).split("\n")) {
            if (line.contains("path: ")) {
                assertThat(line).as("facet line %s", line)
                        .doesNotContain("invoice").doesNotContain("delivery-note");
            }
        }
    }

    @Test
    void documentTypesAreStatedOncePerGroup() {
        String prompt = QueryUnderstanding.buildPrompt(FACETS);

        assertThat(prompt).contains("docType: invoice").contains("docType: delivery-note");
        assertThat(prompt.split("docType: invoice", -1)).hasSize(2);   // exactly one occurrence
    }

    @Test
    void theShapeExampleShowsABarePath() {
        // The model copies the example more reliably than it follows the prose rule.
        assertThat(QueryUnderstanding.buildPrompt(FACETS))
                .contains("{\"path\": \"values.customer\", \"op\": \"eq\", \"value\": \"ACME Corp\"}");
    }

    @Test
    void sampleValuesReachTheModel() {
        assertThat(QueryUnderstanding.buildPrompt(FACETS))
                .contains("ACME Corp").contains("GLOBEX Ltd").contains("NordCargo");
    }
}
