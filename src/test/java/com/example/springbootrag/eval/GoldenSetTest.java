package com.example.springbootrag.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fast guard on the golden-set files. Deliberately untagged so it runs in the normal build:
 * a malformed YAML file should fail here, not two minutes into an eval run.
 */
class GoldenSetTest {

    @Test
    void loadsTheDefaultSelfCorpusSet() {
        List<GoldenEntry> entries = GoldenSet.load();

        assertThat(entries).hasSizeGreaterThanOrEqualTo(10);
        assertThat(entries).allSatisfy(e -> {
            assertThat(e.question()).isNotBlank();
            assertThat(e.expectedDocId()).isNotBlank();
        });
    }

    @Test
    void loadsTheWikiSetByResourceName() {
        List<GoldenEntry> entries = GoldenSet.load("/eval/golden-wiki.yaml");

        // Section A has 11 verified questions today; Section B is empty and may grow later,
        // so assert a floor rather than an exact count.
        assertThat(entries).hasSizeGreaterThanOrEqualTo(11);
        assertThat(entries).allSatisfy(e -> {
            assertThat(e.question()).isNotBlank();
            assertThat(e.expectedDocId()).isNotBlank();
        });
        assertThat(entries.get(0).expectedDocId()).isEqualTo("E-invoicing");
        assertThat(entries.get(0).expectedHeadingPath()).isNull();
    }

    @Test
    void unknownResourceFailsLoudlyAndNamesTheFile() {
        assertThatThrownBy(() -> GoldenSet.load("/eval/does-not-exist.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does-not-exist.yaml");
    }
}
