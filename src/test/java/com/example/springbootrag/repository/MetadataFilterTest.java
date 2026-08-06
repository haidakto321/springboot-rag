package com.example.springbootrag.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetadataFilterTest {

    @Test
    void nullOrBlankJsonIsAnEmptyFilter() {
        assertThat(MetadataFilter.parse(null).isEmpty()).isTrue();
        assertThat(MetadataFilter.parse("  ").isEmpty()).isTrue();
    }

    @Test
    void unknownOpIsRejected() {
        assertThatThrownBy(() -> MetadataFilter.parse("""
                {"filters":[{"path":"values.a","op":"regex","value":"x"}]}"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("regex");
    }

    @Test
    void rangeWithoutABoundIsRejected() {
        assertThatThrownBy(() -> MetadataFilter.parse("""
                {"filters":[{"path":"values.a","op":"range"}]}"""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inWithAnEmptyListIsRejected() {
        assertThatThrownBy(() -> MetadataFilter.parse("""
                {"filters":[{"path":"values.a","op":"in","values":[]}]}"""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eqWithoutAValueIsRejected() {
        assertThatThrownBy(() -> MetadataFilter.parse("""
                {"filters":[{"path":"values.a","op":"eq"}]}"""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedJsonIsRejected() {
        assertThatThrownBy(() -> MetadataFilter.parse("{not json"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void docTypeOnlyFilterIsNotEmpty() {
        assertThat(MetadataFilter.parse("""
                {"docType":"invoice"}""").isEmpty()).isFalse();
    }

    @Test
    void filtersParseIntoConditions() {
        MetadataFilter f = MetadataFilter.parse("""
                {"docType":"invoice",
                 "filters":[{"path":"values.customer","op":"eq","value":"ACME"},
                            {"path":"values.approvedBy","op":"exists"}]}""");

        assertThat(f.docType()).isEqualTo("invoice");
        assertThat(f.conditions()).hasSize(2);
        assertThat(f.conditions().get(1).op()).isEqualTo("exists");
    }
}
