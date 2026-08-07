package com.example.springbootrag.eval;

import com.example.springbootrag.web.dto.RecordRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecordCorpusTest {

    @Test
    void generationIsDeterministic() {
        // The whole point of a committed corpus: identical on every machine and in CI.
        assertThat(RecordCorpus.generate(42)).usingRecursiveComparison()
                .isEqualTo(RecordCorpus.generate(42));
    }

    @Test
    void coversThreeDocumentTypesWithDifferentSchemas() {
        List<RecordRequest> records = RecordCorpus.generate(42);

        assertThat(records).hasSize(210);
        assertThat(records).extracting(RecordRequest::docType)
                .containsOnly("invoice", "delivery-note", "contract");
        assertThat(records).extracting(RecordRequest::docId).doesNotHaveDuplicates();
    }

    @Test
    void everyRecordCarriesAtLeastOneWrappedFieldWithConfidence() {
        assertThat(RecordCorpus.generate(42)).allSatisfy(r ->
                assertThat(r.record().toString()).contains("confidence"));
    }

    @Test
    void goldenSetLoadsAndEveryEntryIsAnswerable() {
        List<RecordGoldenEntry> golden = RecordGoldenSet.load();

        assertThat(golden).hasSizeGreaterThanOrEqualTo(15);
        assertThat(golden).allSatisfy(e -> assertThat(e.question()).isNotBlank());
        // The two cases that keep the design honest.
        assertThat(golden).anyMatch(RecordGoldenEntry::expectNoFilter);
        assertThat(golden).anyMatch(RecordGoldenEntry::expectWiden);
    }

    @Test
    void everyGoldenPathExistsInTheCorpus() {
        // A golden entry naming a path the corpus never produces would measure nothing but the
        // typo - the same class of mistake that made metadata key on leaf names (LEARNINGS 19).
        String corpus = RecordCorpus.generate(42).toString();
        for (RecordGoldenEntry e : RecordGoldenSet.load()) {
            for (var condition : e.expectedFilters()) {
                String path = String.valueOf(condition.get("path"));
                String leaf = path.substring(path.lastIndexOf('.') + 1);
                if (path.startsWith("conf.")) continue;   // computed at ingest, not in the raw record
                assertThat(corpus).as("golden path %s", path).contains("\"" + leaf + "\"");
            }
        }
    }
}
