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
 * Loads and writes the committed retrieval baseline.
 *
 * <p>Reads come from the test classpath, matching {@link GoldenSet}. Writes go to the SOURCE tree,
 * not the classpath copy under {@code target/}, because a regenerated baseline that lands in
 * {@code target/} is discarded by the next build and the update silently does nothing.
 */
public final class EvalBaselineStore {

    static final String RESOURCE = "/eval/baseline-wiki.yaml";
    static final Path SOURCE = Path.of("src", "test", "resources", "eval", "baseline-wiki.yaml");
    private static final String UPDATE_HINT = "regenerate with -Deval.baseline.update=true";

    private EvalBaselineStore() {}

    /** Loads one variant's baseline from the classpath. */
    public static EvalBaseline load(String variant) {
        try (InputStream in = EvalBaselineStore.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        RESOURCE + " not found on the test classpath - " + UPDATE_HINT);
            }
            Map<String, Object> root = new Yaml().load(in);
            return parse(root, variant);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + RESOURCE, e);
        }
    }

    /** Replaces one variant's section in the source file, preserving every other section. */
    public static void write(EvalBaseline baseline) {
        Map<String, Object> existing = Files.exists(SOURCE) ? readSource() : new LinkedHashMap<>();
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        try {
            Files.createDirectories(SOURCE.getParent());
            Files.writeString(SOURCE, new Yaml(options).dump(toMap(baseline, existing)));
        } catch (IOException e) {
            throw new IllegalStateException("could not write " + SOURCE, e);
        }
    }

    private static Map<String, Object> readSource() {
        try (InputStream in = Files.newInputStream(SOURCE)) {
            Map<String, Object> root = new Yaml().load(in);
            return root == null ? new LinkedHashMap<>() : new LinkedHashMap<>(root);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + SOURCE, e);
        }
    }

    /** Merges one variant into an existing root map. Shared keys are overwritten, others kept. */
    static Map<String, Object> toMap(EvalBaseline baseline, Map<String, Object> existing) {
        Map<String, Object> root = new LinkedHashMap<>(existing);

        Map<String, Object> corpus = new LinkedHashMap<>();
        corpus.put("projectId", baseline.corpus().projectId());
        corpus.put("projectName", baseline.corpus().projectName());
        corpus.put("docCount", baseline.corpus().docCount());
        corpus.put("chunkCount", baseline.corpus().chunkCount());
        root.put("corpus", corpus);
        root.put("questions", List.copyOf(baseline.questions()));

        Map<String, Object> metrics = new LinkedHashMap<>();
        baseline.metrics().forEach((backend, m) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("recall5", round3(m.recall5()));
            row.put("mrr", round3(m.mrr()));
            row.put("hit1", round3(m.hit1()));
            metrics.put(backend, row);
        });

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("metrics", metrics);
        section.put("found", new LinkedHashMap<>(baseline.found()));
        root.put(baseline.variant(), section);
        return root;
    }

    @SuppressWarnings("unchecked")
    static EvalBaseline parse(Map<String, Object> root, String variant) {
        Map<String, Object> corpus = (Map<String, Object>) root.get("corpus");
        if (corpus == null) {
            throw new IllegalStateException("baseline has no 'corpus' block - " + UPDATE_HINT);
        }
        CorpusFingerprint fingerprint = new CorpusFingerprint(
                ((Number) corpus.get("projectId")).longValue(),
                (String) corpus.get("projectName"),
                ((Number) corpus.get("docCount")).intValue(),
                ((Number) corpus.get("chunkCount")).intValue());

        List<String> questions = (List<String>) root.get("questions");

        Map<String, Object> section = (Map<String, Object>) root.get(variant);
        if (section == null) {
            throw new IllegalStateException("baseline has no section for reranker variant '"
                    + variant + "' - " + UPDATE_HINT);
        }

        Map<String, BackendMetrics> metrics = new LinkedHashMap<>();
        ((Map<String, Object>) section.get("metrics")).forEach((backend, value) -> {
            Map<String, Object> row = (Map<String, Object>) value;
            metrics.put(backend, new BackendMetrics(
                    ((Number) row.get("recall5")).doubleValue(),
                    ((Number) row.get("mrr")).doubleValue(),
                    ((Number) row.get("hit1")).doubleValue()));
        });

        Map<String, List<String>> found =
                new LinkedHashMap<>((Map<String, List<String>>) section.get("found"));

        return new EvalBaseline(fingerprint, variant, questions, metrics, found);
    }

    /**
     * Keeps the committed file readable at the same 3 decimals the report prints. The rounding
     * error is at most 0.0005 against a tolerance of 0.02, and it only ever lowers the floor.
     */
    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
