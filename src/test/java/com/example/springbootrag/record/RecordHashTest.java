package com.example.springbootrag.record;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecordHashTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void keyOrderDoesNotChangeTheHash() throws Exception {
        String a = RecordHash.ofJson(M.readTree("""
                {"a":1,"b":2}"""));
        String b = RecordHash.ofJson(M.readTree("""
                {"b":2,"a":1}"""));

        assertThat(a).isEqualTo(b);
    }

    @Test
    void differentValuesChangeTheHash() throws Exception {
        assertThat(RecordHash.ofJson(M.readTree("""
                {"a":1}"""))).isNotEqualTo(RecordHash.ofJson(M.readTree("""
                {"a":2}""")));
    }

    @Test
    void arrayOrderDoesChangeTheHash() throws Exception {
        // Arrays are ordered data: line item 1 and line item 2 are not interchangeable.
        assertThat(RecordHash.ofJson(M.readTree("""
                {"a":[1,2]}"""))).isNotEqualTo(RecordHash.ofJson(M.readTree("""
                {"a":[2,1]}""")));
    }

    @Test
    void blockHashIgnoresProvenanceChanges() {
        List<RenderedBlock> before = List.of(
                new RenderedBlock("Customer: ACME", "", Map.of("customer", "ACME"),
                        Map.of("customer", Map.of("confidence", 0.82))));
        List<RenderedBlock> after = List.of(
                new RenderedBlock("Customer: ACME", "", Map.of("customer", "ACME"),
                        Map.of("customer", Map.of("confidence", 0.83))));

        // The point of two hashes: a re-extraction that only jitters a confidence must not
        // re-embed a corpus to produce byte-identical vectors.
        assertThat(RecordHash.ofBlocks(before)).isEqualTo(RecordHash.ofBlocks(after));
    }

    @Test
    void blockHashChangesWhenTextChanges() {
        List<RenderedBlock> before =
                List.of(new RenderedBlock("Customer: ACME", "", Map.of(), Map.of()));
        List<RenderedBlock> after =
                List.of(new RenderedBlock("Customer: OTHER", "", Map.of(), Map.of()));

        assertThat(RecordHash.ofBlocks(before)).isNotEqualTo(RecordHash.ofBlocks(after));
    }

    @Test
    void blockHashChangesWhenBreadcrumbChanges() {
        List<RenderedBlock> before =
                List.of(new RenderedBlock("SKU: A-1", "lineItems[0]", Map.of(), Map.of()));
        List<RenderedBlock> after =
                List.of(new RenderedBlock("SKU: A-1", "lineItems[1]", Map.of(), Map.of()));

        assertThat(RecordHash.ofBlocks(before)).isNotEqualTo(RecordHash.ofBlocks(after));
    }

    @Test
    void hashIsSixtyFourHexChars() throws Exception {
        assertThat(RecordHash.ofJson(M.readTree("{}"))).matches("[0-9a-f]{64}");
    }
}
