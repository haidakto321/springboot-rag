package com.example.springbootrag.record;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ValueWrapperTest {

    private static final ObjectMapper M = new ObjectMapper();

    private ValueWrapper.Unwrapped detect(String json) throws Exception {
        Optional<ValueWrapper.Unwrapped> u =
                ValueWrapper.detect(M.readTree(json), ValueWrapper.Keys.DEFAULT);
        return u.orElse(null);
    }

    @Test
    void unwrapsValueAndKeepsProvenanceOutOfTheValue() throws Exception {
        ValueWrapper.Unwrapped u = detect("""
                {"value":"ACME Corp","confidence":0.82,
                 "grounding":{"page":2,"bbox":[12,44,90,60]}}""");

        assertThat(u).isNotNull();
        assertThat(u.value().asText()).isEqualTo("ACME Corp");
        assertThat(u.provenance()).containsEntry("confidence", 0.82);
        assertThat(u.provenance()).containsEntry("page", 2);
        assertThat(u.provenance()).containsKey("bbox");
    }

    @Test
    void acceptsAlternateValueKeys() throws Exception {
        assertThat(detect("""
                {"text":"hello","score":0.5}""").value().asText()).isEqualTo("hello");
        assertThat(detect("""
                {"content":"hi","confidence":0.5}""").value().asText()).isEqualTo("hi");
    }

    @Test
    void unknownExtraKeyMeansNotAWrapper() throws Exception {
        // Failing open: an unrecognised key may be real extracted data, and dropping it silently
        // is worse than a little noise in the rendered text.
        assertThat(detect("""
                {"value":"ACME","confidence":0.9,"legalForm":"GmbH"}""")).isNull();
    }

    @Test
    void plainObjectIsNotAWrapper() throws Exception {
        assertThat(detect("""
                {"name":"ACME","city":"Berlin"}""")).isNull();
    }

    @Test
    void objectWithTwoValueKeysIsNotAWrapper() throws Exception {
        assertThat(detect("""
                {"value":"a","text":"b","confidence":0.5}""")).isNull();
    }

    @Test
    void wrapperWithNoProvenanceStillUnwraps() throws Exception {
        assertThat(detect("""
                {"value":"ACME"}""").provenance()).isEmpty();
    }

    @Test
    void nonNumericConfidenceIsKeptRawAndNotCoerced() throws Exception {
        ValueWrapper.Unwrapped u = detect("""
                {"value":"ACME","confidence":"high"}""");

        assertThat(u.provenance()).containsEntry("confidence_raw", "high");
        assertThat(u.provenance()).doesNotContainKey("confidence");
    }

    @Test
    void customKeysFromProfileAreHonoured() throws Exception {
        ValueWrapper.Keys keys = new ValueWrapper.Keys(
                Set.of("val"), Set.of("certainty"), Set.of("locator"));

        Optional<ValueWrapper.Unwrapped> u = ValueWrapper.detect(M.readTree("""
                {"val":"ACME","certainty":0.7,"locator":{"page":1}}"""), keys);

        assertThat(u).isPresent();
        assertThat(u.get().value().asText()).isEqualTo("ACME");
        assertThat(u.get().provenance()).containsEntry("confidence", 0.7);
        assertThat(u.get().provenance()).containsEntry("page", 1);
    }

    @Test
    void nonObjectIsNotAWrapper() throws Exception {
        assertThat(detect("\"plain string\"")).isNull();
        assertThat(detect("42")).isNull();
    }
}
