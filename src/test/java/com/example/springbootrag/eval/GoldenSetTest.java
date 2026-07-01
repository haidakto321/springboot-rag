package com.example.springbootrag.eval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GoldenSetTest {

    @Test
    void loadsEntriesWithRequiredFields() {
        var entries = GoldenSet.load();
        assertThat(entries).hasSizeGreaterThanOrEqualTo(10);
        assertThat(entries).allSatisfy(e -> {
            assertThat(e.question()).isNotBlank();
            assertThat(e.expectedDocId()).isNotBlank();
        });
    }
}
