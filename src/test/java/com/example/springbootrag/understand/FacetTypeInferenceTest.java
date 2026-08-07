package com.example.springbootrag.understand;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FacetTypeInferenceTest {

    @Test
    void allNumbersInferNumber() {
        assertThat(FacetCatalogue.inferType(List.of("1899.5", "42", "0"))).isEqualTo("number");
    }

    @Test
    void allIsoDatesInferDate() {
        assertThat(FacetCatalogue.inferType(List.of("2026-05-02", "2026-01-14"))).isEqualTo("date");
    }

    @Test
    void anythingElseIsText() {
        assertThat(FacetCatalogue.inferType(List.of("ACME Corp", "GLOBEX"))).isEqualTo("text");
    }

    @Test
    void oneOddValueDowngradesToText() {
        // Inference only picks the cast the filter DSL will use, so a mixed column must degrade
        // to a text comparison rather than produce a cast error at query time.
        assertThat(FacetCatalogue.inferType(List.of("42", "n/a"))).isEqualTo("text");
    }

    @Test
    void noSamplesIsText() {
        assertThat(FacetCatalogue.inferType(List.of())).isEqualTo("text");
    }
}
