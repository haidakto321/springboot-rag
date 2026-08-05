package com.example.springbootrag.eval;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class EvalBaselineStoreTest {

    private static final CorpusFingerprint CORPUS =
            new CorpusFingerprint(5L, "docmaster", 428, 7536);
    private static final String Q1 = "Which two electronic-invoice formats are used for Germany?";

    private static EvalBaseline sample(String variant, double mrr) {
        Map<String, BackendMetrics> metrics = new LinkedHashMap<>();
        metrics.put("hybrid", new BackendMetrics(0.909, mrr, 0.909));
        Map<String, List<String>> found = new LinkedHashMap<>();
        found.put("hybrid", List.of(Q1));
        return new EvalBaseline(CORPUS, variant, List.of(Q1), metrics, found);
    }

    @Test
    void roundTripsThroughTheYamlShape() {
        EvalBaseline original = sample("identity", 0.919);

        Map<String, Object> root = EvalBaselineStore.toMap(original, new LinkedHashMap<>());
        EvalBaseline parsed = EvalBaselineStore.parse(root, "identity");

        assertThat(parsed.corpus()).isEqualTo(CORPUS);
        assertThat(parsed.variant()).isEqualTo("identity");
        assertThat(parsed.questions()).containsExactly(Q1);
        assertThat(parsed.found()).containsEntry("hybrid", List.of(Q1));
        assertThat(parsed.metrics().get("hybrid").mrr()).isCloseTo(0.919, within(0.001));
    }

    /** Writing one variant must leave the other variant's numbers untouched. */
    @Test
    void writingOneVariantPreservesTheOther() {
        Map<String, Object> root = EvalBaselineStore.toMap(sample("identity", 0.919), new LinkedHashMap<>());
        root = EvalBaselineStore.toMap(sample("djl", 0.909), root);

        assertThat(EvalBaselineStore.parse(root, "identity").metrics().get("hybrid").mrr())
                .isCloseTo(0.919, within(0.001));
        assertThat(EvalBaselineStore.parse(root, "djl").metrics().get("hybrid").mrr())
                .isCloseTo(0.909, within(0.001));
    }

    @Test
    void parsingAnUnknownVariantNamesTheRemedy() {
        Map<String, Object> root = EvalBaselineStore.toMap(sample("identity", 0.919), new LinkedHashMap<>());

        assertThatThrownBy(() -> EvalBaselineStore.parse(root, "djl"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("djl")
                .hasMessageContaining("-Deval.baseline.update=true");
    }

    /** Metrics are rounded to 3 decimals on write so the committed file stays readable. */
    @Test
    void roundsMetricsToThreeDecimalsOnWrite() {
        Map<String, BackendMetrics> metrics = new LinkedHashMap<>();
        metrics.put("hybrid", new BackendMetrics(0.9090909090909091, 0.9191919191919192, 0.909));
        Map<String, List<String>> found = new LinkedHashMap<>();
        found.put("hybrid", List.of(Q1));
        EvalBaseline original = new EvalBaseline(CORPUS, "identity", List.of(Q1), metrics, found);

        EvalBaseline parsed = EvalBaselineStore.parse(
                EvalBaselineStore.toMap(original, new LinkedHashMap<>()), "identity");

        assertThat(parsed.metrics().get("hybrid").recall5()).isEqualTo(0.909);
        assertThat(parsed.metrics().get("hybrid").mrr()).isEqualTo(0.919);
    }

    /**
     * Covers the real serialization path, which toMap/parse alone do not: the map is dumped to YAML
     * text and loaded back. Question strings carry punctuation that YAML must quote correctly.
     * Deliberately does not touch EvalBaselineStore.SOURCE - a unit test must not dirty the repo.
     */
    @Test
    void survivesAnActualYamlDumpAndLoad() {
        Map<String, Object> root = EvalBaselineStore.toMap(sample("identity", 0.919), new LinkedHashMap<>());
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);

        String yaml = new Yaml(options).dump(root);
        Map<String, Object> reloaded = new Yaml().load(yaml);
        EvalBaseline parsed = EvalBaselineStore.parse(reloaded, "identity");

        assertThat(parsed.corpus()).isEqualTo(CORPUS);
        assertThat(parsed.questions()).containsExactly(Q1);
        assertThat(parsed.found()).containsEntry("hybrid", List.of(Q1));
        assertThat(parsed.metrics().get("hybrid").mrr()).isCloseTo(0.919, within(0.001));
    }
}
