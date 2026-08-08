package com.example.springbootrag.eval;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads and writes the committed record-eval baseline.
 *
 * <p>Reads come from the test classpath; writes go to the SOURCE tree, not the classpath copy under
 * {@code target/} - a regenerated baseline that lands in {@code target/} is discarded by the next
 * build and the update silently does nothing. Same trap, same fix, as {@link EvalBaselineStore}.
 */
public final class RecordEvalBaselineStore {

    static final String RESOURCE = "/eval/baseline-records.yaml";
    static final Path SOURCE = Path.of("src", "test", "resources", "eval", "baseline-records.yaml");
    private static final String UPDATE_HINT = "regenerate with -Deval.baseline.update=true";

    private RecordEvalBaselineStore() {}

    public static boolean exists() {
        return RecordEvalBaselineStore.class.getResourceAsStream(RESOURCE) != null;
    }

    public static RecordEvalBaseline load() {
        try (InputStream in = RecordEvalBaselineStore.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        RESOURCE + " not found on the test classpath - " + UPDATE_HINT);
            }
            return parse(new Yaml().load(in));
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + RESOURCE, e);
        }
    }

    public static void write(RecordEvalBaseline baseline) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        try {
            Files.createDirectories(SOURCE.getParent());
            Files.writeString(SOURCE, new Yaml(options).dump(toMap(baseline)));
        } catch (IOException e) {
            throw new IllegalStateException("could not write " + SOURCE, e);
        }
    }

    static Map<String, Object> toMap(RecordEvalBaseline b) {
        Map<String, Object> root = new LinkedHashMap<>();

        Map<String, Object> corpus = new LinkedHashMap<>();
        corpus.put("seed", b.corpusSeed());
        corpus.put("records", b.corpusSize());
        root.put("corpus", corpus);
        root.put("questions", List.copyOf(b.questions()));

        Map<String, Object> extraction = new LinkedHashMap<>();
        extraction.put("conditionPrecision", round3(b.extraction().conditionPrecision()));
        extraction.put("conditionRecall", round3(b.extraction().conditionRecall()));
        extraction.put("docTypeAccuracy", round3(b.extraction().docTypeAccuracy()));
        extraction.put("noFilterCorrect", b.extraction().noFilterCorrect());
        root.put("extraction", extraction);

        Map<String, Object> retrieval = new LinkedHashMap<>();
        b.retrieval().forEach((key, m) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("recall5", round3(m.recall5()));
            row.put("mrr", round3(m.mrr()));
            row.put("hit1", round3(m.hit1()));
            retrieval.put(key, row);
        });
        root.put("retrieval", retrieval);
        root.put("filtered", List.copyOf(b.filtered()));
        return root;
    }

    @SuppressWarnings("unchecked")
    static RecordEvalBaseline parse(Map<String, Object> root) {
        if (root == null || !root.containsKey("corpus")) {
            throw new IllegalStateException("baseline has no 'corpus' block - " + UPDATE_HINT);
        }
        Map<String, Object> corpus = (Map<String, Object>) root.get("corpus");
        Map<String, Object> extraction = (Map<String, Object>) root.get("extraction");
        if (extraction == null) {
            throw new IllegalStateException("baseline has no 'extraction' block - " + UPDATE_HINT);
        }

        Map<String, BackendMetrics> retrieval = new LinkedHashMap<>();
        ((Map<String, Object>) root.get("retrieval")).forEach((key, value) -> {
            Map<String, Object> row = (Map<String, Object>) value;
            retrieval.put(key, new BackendMetrics(
                    ((Number) row.get("recall5")).doubleValue(),
                    ((Number) row.get("mrr")).doubleValue(),
                    ((Number) row.get("hit1")).doubleValue()));
        });

        return new RecordEvalBaseline(
                ((Number) corpus.get("seed")).longValue(),
                ((Number) corpus.get("records")).intValue(),
                (List<String>) root.get("questions"),
                new RecordEvalBaseline.Extraction(
                        ((Number) extraction.get("conditionPrecision")).doubleValue(),
                        ((Number) extraction.get("conditionRecall")).doubleValue(),
                        ((Number) extraction.get("docTypeAccuracy")).doubleValue(),
                        ((Number) extraction.get("noFilterCorrect")).intValue()),
                retrieval,
                (List<String>) root.getOrDefault("filtered", List.of()));
    }

    /** Keeps the committed file readable at the 3 decimals the report prints. */
    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
