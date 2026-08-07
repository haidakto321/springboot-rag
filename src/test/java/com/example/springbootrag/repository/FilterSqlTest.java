package com.example.springbootrag.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilterSqlTest {

    @Test
    void emptyFilterProducesNoPredicate() {
        // The most dangerous case: an empty filter must mean "no filter", never a predicate that
        // matches nothing (LEARNINGS section 13, the empty Qdrant should-clause).
        FilterSql.Fragment f = FilterSql.render(MetadataFilter.none());

        assertThat(f.sql()).isEmpty();
        assertThat(f.args()).isEmpty();
    }

    @Test
    void eqRendersAJsonbTextComparison() {
        FilterSql.Fragment f = FilterSql.render(MetadataFilter.parse("""
                {"filters":[{"path":"values.customer.name","op":"eq","value":"ACME"}]}"""));

        assertThat(f.sql()).isEqualTo(" AND metadata #>> '{values,customer,name}' = ?");
        assertThat(f.args()).containsExactly("ACME");
    }

    @Test
    void docTypeBecomesItsOwnColumnPredicate() {
        FilterSql.Fragment f = FilterSql.render(MetadataFilter.parse("""
                {"docType":"invoice"}"""));

        assertThat(f.sql()).isEqualTo(" AND doc_type = ?");
        assertThat(f.args()).containsExactly("invoice");
    }

    @Test
    void inRendersOnePlaceholderPerValue() {
        FilterSql.Fragment f = FilterSql.render(MetadataFilter.parse("""
                {"filters":[{"path":"values.status","op":"in","values":["open","overdue"]}]}"""));

        assertThat(f.sql()).isEqualTo(" AND metadata #>> '{values,status}' IN (?,?)");
        assertThat(f.args()).containsExactly("open", "overdue");
    }

    @Test
    void numberRangeCastsBothSides() {
        FilterSql.Fragment f = FilterSql.render(MetadataFilter.parse("""
                {"filters":[{"path":"conf.min","op":"range","gte":0.7,"type":"number"}]}"""));

        assertThat(f.sql()).isEqualTo(" AND (metadata #>> '{conf,min}')::numeric >= ?::numeric");
        assertThat(f.args()).hasSize(1);
    }

    @Test
    void dateRangeCastsBothSidesNotJustTheColumn() {
        // Casting only the column gives Postgres `timestamptz >= varchar`, which it refuses:
        // "operator does not exist". Found by RecordFilterEvalTest, missed here for a month
        // because this test asserted the generated string instead of executing it.
        FilterSql.Fragment f = FilterSql.render(MetadataFilter.parse("""
                {"filters":[{"path":"values.issueDate","op":"range",
                             "gte":"2026-04-01","lt":"2026-07-01","type":"date"}]}"""));

        assertThat(f.sql()).contains("::timestamptz >= ?::timestamptz")
                .contains("::timestamptz < ?::timestamptz");
        assertThat(f.args()).containsExactly("2026-04-01", "2026-07-01");
    }

    @Test
    void eqOnANumericValueBindsItAsText() {
        // metadata #>> path yields text, so `text = 5000` (an int bind) is the same type error.
        FilterSql.Fragment f = FilterSql.render(MetadataFilter.parse("""
                {"filters":[{"path":"values.total","op":"eq","value":5000}]}"""));

        assertThat(f.sql()).isEqualTo(" AND metadata #>> '{values,total}' = ?");
        assertThat(f.args()).containsExactly("5000");
    }

    @Test
    void inOnNumericValuesBindsThemAsText() {
        FilterSql.Fragment f = FilterSql.render(MetadataFilter.parse("""
                {"filters":[{"path":"values.total","op":"in","values":[100,200]}]}"""));

        assertThat(f.args()).containsExactly("100", "200");
    }

    @Test
    void existsChecksForANonNullPath() {
        FilterSql.Fragment f = FilterSql.render(MetadataFilter.parse("""
                {"filters":[{"path":"values.approvedBy","op":"exists"}]}"""));

        assertThat(f.sql()).isEqualTo(" AND metadata #>> '{values,approvedBy}' IS NOT NULL");
        assertThat(f.args()).isEmpty();
    }

    @Test
    void multipleConditionsAndTogether() {
        FilterSql.Fragment f = FilterSql.render(MetadataFilter.parse("""
                {"docType":"invoice",
                 "filters":[{"path":"values.status","op":"eq","value":"open"},
                            {"path":"values.total","op":"range","gt":100,"type":"number"}]}"""));

        assertThat(f.sql()).startsWith(" AND doc_type = ?");
        assertThat(f.args()).hasSize(3);
        assertThat(f.args().get(0)).isEqualTo("invoice");
        assertThat(f.args().get(1)).isEqualTo("open");
    }

    @Test
    void pathIsValidatedAgainstInjection() {
        // Paths are interpolated into a #>> '{...}' literal, never bound, so anything outside
        // [A-Za-z0-9_-] is rejected rather than escaped.
        assertThatThrownBy(() -> FilterSql.render(MetadataFilter.parse("""
                {"filters":[{"path":"values.a'} , '{b","op":"exists"}]}""")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void arrayMarkersAreStrippedFromPaths() {
        // An array element is its own chunk carrying its own scalars, so there is no array left
        // in the stored metadata to index into.
        FilterSql.Fragment f = FilterSql.render(MetadataFilter.parse("""
                {"filters":[{"path":"values.lineItems[].sku","op":"eq","value":"A-1"}]}"""));

        assertThat(f.sql()).isEqualTo(" AND metadata #>> '{values,lineItems,sku}' = ?");
    }
}
