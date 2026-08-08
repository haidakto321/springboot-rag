package com.example.springbootrag.eval;

import java.util.List;
import java.util.Map;

/**
 * One measured or expected run of the record query-understanding eval.
 *
 * <p>Separate from {@link EvalBaseline} because what this eval measures is different: the wiki gate
 * scores six retrieval backends on three numbers each, while this one scores how well a question
 * turns into a filter, and only then what that filter does to retrieval. Packing extraction
 * precision into a field called {@code recall5} would have reused the machinery at the cost of
 * every future reader.
 *
 * @param corpusSeed        the {@link RecordCorpus} seed, so a regenerated corpus is detectable
 * @param corpusSize        number of records the seed produced
 * @param questions         the whole golden set in file order, so a NEW question is
 *                          distinguishable from one that regressed
 * @param extraction        condition precision / recall, docType accuracy, no-filter correctness
 * @param retrieval         "with-extraction" and "without-extraction" to their retrieval metrics
 * @param filtered          questions for which extraction produced a non-empty filter, so losing
 *                          one is caught even when the aggregate barely moves
 */
public record RecordEvalBaseline(
        long corpusSeed,
        int corpusSize,
        List<String> questions,
        Extraction extraction,
        Map<String, BackendMetrics> retrieval,
        List<String> filtered) {

    /**
     * How well questions became filters.
     *
     * @param conditionPrecision matched conditions / conditions the model produced
     * @param conditionRecall    matched conditions / conditions the golden set expects
     * @param docTypeAccuracy    share of questions whose expected docType was extracted
     * @param noFilterCorrect    of the questions that must produce NO filter, how many did.
     *                           A count, not a ratio: there are two of them and a ratio hides
     *                           which one broke.
     */
    public record Extraction(double conditionPrecision, double conditionRecall,
                             double docTypeAccuracy, int noFilterCorrect) {}
}
