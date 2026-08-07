package com.example.springbootrag.eval;

import com.example.springbootrag.web.dto.RecordRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ground truth decides what every eval number means, so it is tested like production code.
 * A quietly wrong matcher would not fail anything - it would just print confident nonsense.
 */
class RecordGroundTruthTest {

    private static final List<RecordRequest> CORPUS = RecordCorpus.generate(42);

    @Test
    void unwrapsAValueWrapperBeforeComparing() {
        RecordRequest first = CORPUS.getFirst();
        // customer is {"value":...,"confidence":...,"grounding":{...}}
        assertThat(RecordGroundTruth.values(first, "values.customer"))
                .singleElement().asString().isIn(RecordCorpus.CUSTOMERS);
    }

    @Test
    void readsThroughAnArrayToItsElements() {
        assertThat(RecordGroundTruth.values(CORPUS.getFirst(), "values.lineItems.sku"))
                .singleElement().asString().startsWith("SKU-");
    }

    @Test
    void confMinIsTheSmallestReportedConfidence() {
        // The invoice generator reports the customer confidence and nothing else, so min == that.
        List<String> conf = RecordGroundTruth.values(CORPUS.getFirst(), "conf.min");
        assertThat(conf).singleElement().asString().isNotBlank();
        assertThat(Double.parseDouble(conf.getFirst())).isBetween(0.4, 1.0);
    }

    @Test
    void docTypeNarrowsTheMatchSet() {
        RecordGoldenEntry entry = new RecordGoldenEntry("delivery notes by Speedy Freight",
                "delivery-note",
                List.of(Map.of("path", "values.carrier", "op", "eq", "value", "Speedy Freight")),
                false, false);

        List<String> ids = RecordGroundTruth.matchingDocIds(CORPUS, entry);

        assertThat(ids).isNotEmpty().allMatch(id -> id.startsWith("DN-"));
    }

    @Test
    void aNumericRangeComparesAsNumbersNotStrings() {
        // "9" > "10" as text; the whole point is that it must not.
        RecordGoldenEntry entry = new RecordGoldenEntry("invoices over 5000", "invoice",
                List.of(Map.of("path", "values.total", "op", "range", "gt", 5000)), false, false);

        List<String> ids = RecordGroundTruth.matchingDocIds(CORPUS, entry);

        assertThat(ids).isNotEmpty();
        assertThat(ids.size()).isLessThan(120);   // not every invoice qualifies
    }

    @Test
    void aDateRangeUsesIsoOrdering() {
        RecordGoldenEntry q2 = new RecordGoldenEntry("Q2 invoices", "invoice",
                List.of(Map.of("path", "values.issueDate", "op", "range",
                        "gte", "2026-04-01", "lt", "2026-07-01")), false, false);

        assertThat(RecordGroundTruth.matchingDocIds(CORPUS, q2)).isNotEmpty();
    }

    @Test
    void everyGoldenQuestionWithAFilterMatchesSomething() {
        // A golden entry matching zero records measures nothing but its own typo.
        for (RecordGoldenEntry entry : RecordGoldenSet.load()) {
            if (entry.expectedFilters().isEmpty() || entry.expectWiden()) continue;
            assertThat(RecordGroundTruth.matchingDocIds(CORPUS, entry))
                    .as("golden question '%s'", entry.question())
                    .isNotEmpty();
        }
    }
}
