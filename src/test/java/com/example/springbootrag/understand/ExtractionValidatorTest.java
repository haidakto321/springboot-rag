package com.example.springbootrag.understand;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractionValidatorTest {

    private static final List<Facet> FACETS = List.of(
            new Facet("invoice", "values.customer", "text", List.of("ACME Corp"), 3),
            new Facet("invoice", "values.total", "number", List.of("1899.5"), 9),
            new Facet("invoice", "values.issueDate", "date", List.of("2026-05-02"), 40),
            new Facet("delivery-note", "values.carrier", "text", List.of("Speedy Freight"), 2));

    private ExtractionValidator.Result validate(String json) {
        return ExtractionValidator.validate(json, FACETS, 4, 200);
    }

    @Test
    void keepsAConditionOnAKnownPath() {
        ExtractionValidator.Result r = validate("""
                {"docType":"invoice",
                 "filters":[{"path":"values.customer","op":"eq","value":"ACME Corp"}]}""");

        assertThat(r.filter().docType()).isEqualTo("invoice");
        assertThat(r.filter().conditions()).hasSize(1);
        assertThat(r.dropped()).isEmpty();
    }

    @Test
    void dropsAnInventedPath() {
        // The model hallucinating a field is the expected case, not the exceptional one.
        ExtractionValidator.Result r = validate("""
                {"filters":[{"path":"values.vendorName","op":"eq","value":"ACME"}]}""");

        assertThat(r.filter().conditions()).isEmpty();
        assertThat(r.dropped()).anyMatch(s -> s.contains("values.vendorName"));
    }

    @Test
    void dropsAnUnknownDocTypeButKeepsTheConditions() {
        ExtractionValidator.Result r = validate("""
                {"docType":"purchase-order",
                 "filters":[{"path":"values.customer","op":"eq","value":"ACME Corp"}]}""");

        assertThat(r.filter().docType()).isNull();
        assertThat(r.filter().conditions()).hasSize(1);
        assertThat(r.dropped()).anyMatch(s -> s.contains("purchase-order"));
    }

    @Test
    void appliesTheFacetTypeSoRangesCastCorrectly() {
        ExtractionValidator.Result r = validate("""
                {"filters":[{"path":"values.total","op":"range","gte":1000}]}""");

        assertThat(r.filter().conditions().get(0).type()).isEqualTo("number");
    }

    @Test
    void truncatesTooManyConditions() {
        ExtractionValidator.Result r = ExtractionValidator.validate("""
                {"filters":[{"path":"values.customer","op":"eq","value":"a"},
                            {"path":"values.customer","op":"eq","value":"b"},
                            {"path":"values.customer","op":"eq","value":"c"}]}""",
                FACETS, 2, 200);

        assertThat(r.filter().conditions()).hasSize(2);
        assertThat(r.dropped()).anyMatch(s -> s.contains("too many"));
    }

    @Test
    void dropsAnOversizedValue() {
        ExtractionValidator.Result r = validate("""
                {"filters":[{"path":"values.customer","op":"eq","value":"%s"}]}"""
                .formatted("x".repeat(500)));

        assertThat(r.filter().conditions()).isEmpty();
    }

    @Test
    void rewritesABareComparisonOpIntoARange() {
        // What qwen3:4b actually returns for "invoices over 5000" - measured, not hypothetical.
        ExtractionValidator.Result r = validate("""
                {"filters":[{"path":"values.total","op":"gt","value":5000}]}""");

        assertThat(r.filter().conditions()).hasSize(1);
        var c = r.filter().conditions().get(0);
        assertThat(c.op()).isEqualTo("range");
        assertThat(c.gt()).isEqualTo(5000);
        assertThat(c.value()).isNull();
        assertThat(c.type()).isEqualTo("number");
    }

    @Test
    void rewritesABareBoundGivenUnderItsOwnKey() {
        ExtractionValidator.Result r = validate("""
                {"filters":[{"path":"values.issueDate","op":"gte","gte":"2026-04-01"}]}""");

        assertThat(r.filter().conditions()).hasSize(1);
        assertThat(r.filter().conditions().get(0).op()).isEqualTo("range");
        assertThat(r.filter().conditions().get(0).gte()).isEqualTo("2026-04-01");
    }

    @Test
    void aBareComparisonOpWithNoBoundIsStillDropped() {
        assertThat(validate("""
                {"filters":[{"path":"values.total","op":"gt"}]}""")
                .filter().conditions()).isEmpty();
    }

    @Test
    void dropsAMalformedCondition() {
        // Unknown op, and a range with no bound - MetadataFilter.parse would throw on these, and
        // a model mistake must not become a 500.
        assertThat(validate("""
                {"filters":[{"path":"values.customer","op":"regex","value":"AC.*"}]}""")
                .filter().conditions()).isEmpty();
        assertThat(validate("""
                {"filters":[{"path":"values.total","op":"range"}]}""")
                .filter().conditions()).isEmpty();
    }

    @Test
    void unparseableOutputIsAnEmptyFilterNotAnError() {
        assertThat(validate("I think you want invoices for ACME").filter().isEmpty()).isTrue();
        assertThat(validate("").filter().isEmpty()).isTrue();
        assertThat(validate(null).filter().isEmpty()).isTrue();
    }

    @Test
    void findsJsonWrappedInProse() {
        ExtractionValidator.Result r = validate("""
                Sure! Here is the filter:
                {"docType":"invoice","filters":[]}
                Hope that helps.""");

        assertThat(r.filter().docType()).isEqualTo("invoice");
    }

    @Test
    void everythingDroppedMeansNoFilterNotAFilterMatchingNothing() {
        ExtractionValidator.Result r = validate("""
                {"filters":[{"path":"values.nope","op":"eq","value":"x"}]}""");

        // The standing trap (LEARNINGS section 13): "no filter" must render no predicate at all.
        assertThat(r.filter().isEmpty()).isTrue();
    }
}
