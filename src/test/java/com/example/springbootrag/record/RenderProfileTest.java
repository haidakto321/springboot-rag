package com.example.springbootrag.record;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenderProfileTest {

    @Test
    void excludeWinsOverInclude() {
        RenderProfile p = RenderProfile.parse("""
                {"include":["a","b"],"exclude":["b"]}""");

        assertThat(p.isExcluded("b")).isTrue();
        assertThat(p.isExcluded("a")).isFalse();
    }

    @Test
    void emptyIncludeMeansEverythingNotExcluded() {
        RenderProfile p = RenderProfile.parse("""
                {"exclude":["secret"]}""");

        assertThat(p.isExcluded("anything")).isFalse();
        assertThat(p.isExcluded("secret")).isTrue();
    }

    @Test
    void nonEmptyIncludeExcludesEverythingElse() {
        RenderProfile p = RenderProfile.parse("""
                {"include":["customer.name"]}""");

        assertThat(p.isExcluded("customer.name")).isFalse();
        assertThat(p.isExcluded("internalNotes")).isTrue();
    }

    @Test
    void wildcardExcludeMatchesAPrefix() {
        RenderProfile p = RenderProfile.parse("""
                {"exclude":["internal.*"]}""");

        assertThat(p.isExcluded("internal.batchId")).isTrue();
        assertThat(p.isExcluded("internalish")).isFalse();
    }

    @Test
    void labelsOverrideTheDerivedLabel() {
        RenderProfile p = RenderProfile.parse("""
                {"labels":{"issueDate":"Invoice date"}}""");

        assertThat(p.labelFor("issueDate")).isEqualTo("Invoice date");
        assertThat(p.labelFor("total")).isEqualTo("Total");
    }

    @Test
    void filterOnlyPathsAreMarked() {
        RenderProfile p = RenderProfile.parse("""
                {"filterOnly":["internal.batchId"]}""");

        assertThat(p.isFilterOnly("internal.batchId")).isTrue();
        assertThat(p.isFilterOnly("customer")).isFalse();
    }

    @Test
    void wrapperKeysComeFromTheProfileWhenDeclared() {
        RenderProfile p = RenderProfile.parse("""
                {"wrapper":{"valueKeys":["val"],"confidenceKeys":["certainty"],
                            "groundingKeys":["locator"]}}""");

        assertThat(p.wrapperKeys().valueKeys()).containsExactly("val");
        assertThat(p.wrapperKeys().confidenceKeys()).containsExactly("certainty");
    }

    @Test
    void wrapperKeysFallBackToDefaults() {
        assertThat(RenderProfile.parse("{}").wrapperKeys()).isEqualTo(ValueWrapper.Keys.DEFAULT);
    }

    @Test
    void malformedJsonIsRejected() {
        assertThatThrownBy(() -> RenderProfile.parse("not json"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
