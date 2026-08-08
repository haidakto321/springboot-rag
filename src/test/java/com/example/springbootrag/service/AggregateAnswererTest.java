package com.example.springbootrag.service;

import com.example.springbootrag.repository.MetadataFilter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AggregateAnswererTest {

    private static final MetadataFilter ACME = MetadataFilter.parse("""
            {"docType":"invoice","filters":[{"path":"values.customer","op":"eq","value":"ACME Corp"}]}""");

    @Test
    void statesTheCountAndTheFilterThatProducedIt() {
        String answer = AggregateAnswerer.answer(7, ACME);

        assertThat(answer).contains("7").contains("invoice")
                .contains("values.customer = ACME Corp");
    }

    @Test
    void oneRecordReadsAsOneRecord() {
        assertThat(AggregateAnswerer.answer(1, ACME)).contains("1 invoice record matches");
    }

    @Test
    void zeroIsAnAnswerNotAFailure() {
        // Aggregate never widens: a true zero must survive. The filter is printed beside it so a
        // typo'd customer name is visible as the cause instead of reading as "we have none".
        String answer = AggregateAnswerer.answer(0, ACME);

        assertThat(answer).contains("0 invoice records match");
        assertThat(answer).contains("values.customer = ACME Corp");
    }

    @Test
    void anEmptyFilterCountsTheWholeScope() {
        assertThat(AggregateAnswerer.answer(210, MetadataFilter.none()))
                .isEqualTo("210 records match.");
    }

    @Test
    void aDocTypeOnlyFilterNamesTheTypeWithoutAWhereClause() {
        MetadataFilter notes = MetadataFilter.parse("""
                {"docType":"delivery-note"}""");

        assertThat(AggregateAnswerer.answer(60, notes))
                .isEqualTo("60 delivery-note records match.");
    }

    @Test
    void aRangeConditionIsRenderedReadably() {
        MetadataFilter over = MetadataFilter.parse("""
                {"docType":"invoice","filters":[{"path":"values.total","op":"range","gt":5000}]}""");

        assertThat(AggregateAnswerer.answer(3, over)).contains("values.total > 5000");
    }

    @Test
    void anInConditionListsItsValues() {
        MetadataFilter status = MetadataFilter.parse("""
                {"filters":[{"path":"values.status","op":"in","values":["open","overdue"]}]}""");

        assertThat(AggregateAnswerer.answer(42, status)).contains("values.status in [open, overdue]");
    }

    @Test
    void theChitchatReplyMakesNoClaimAboutTheCorpus() {
        // It must not say what is in the knowledge base: nothing was retrieved to know that.
        assertThat(AggregateAnswerer.CHITCHAT_REPLY).isNotBlank();
        assertThat(AggregateAnswerer.CHITCHAT_REPLY).doesNotContain("[1]");
    }
}
