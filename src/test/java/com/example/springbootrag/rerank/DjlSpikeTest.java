package com.example.springbootrag.rerank;

// CONFIRMED working imports for DJL 0.30.0 (resolved against real jars in Task 1).
// NOTE: the plan's `CrossEncoderTranslatorFactory` does NOT exist in any DJL release.
// Real API: build a CrossEncoderTranslator from a HuggingFaceTokenizer and pass it
// via Criteria.optTranslator(...). StringPair lives in ai.djl.util, not modality.nlp.
// DjlReranker reuses exactly this load path.
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.translator.CrossEncoderTranslator;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.util.StringPair;
import com.example.springbootrag.config.RerankProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the raw DJL load path directly, without going through {@link DjlReranker}, so a DJL
 * API or model-availability break can be told apart from a break in our wrapper.
 * {@link DjlRerankerManualTest} covers the wrapper itself.
 *
 * <p>The model id comes from {@link RerankProperties} rather than a literal. It used to be
 * hardcoded, and that copy drifted from the configured value: the id it named
 * ({@code BAAI/bge-reranker-base}) is not published in DJL's zoo, so this test and production
 * both failed with "Invalid djl URL" - a message that reads like a syntax error and sent the
 * original diagnosis toward blocked network access instead of a missing catalog entry.
 */
@EnabledIfEnvironmentVariable(named = "RUN_DJL_SPIKE", matches = "true")
class DjlSpikeTest {

    @Test
    void crossEncoderScoresRelevantPairHigher() throws Exception {
        String model = new RerankProperties().getModel();

        HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(model);
        CrossEncoderTranslator translator = CrossEncoderTranslator.builder(tokenizer)
                .optSigmoid(true)
                .build();

        Criteria<StringPair, float[]> criteria = Criteria.builder()
                .setTypes(StringPair.class, float[].class)
                .optModelUrls("djl://ai.djl.huggingface.pytorch/" + model)
                .optEngine("PyTorch")
                .optTranslator(translator)
                .build();

        try (ZooModel<StringPair, float[]> zooModel = criteria.loadModel();
             Predictor<StringPair, float[]> predictor = zooModel.newPredictor()) {

            String query = "how to restart the payment service after an outage";
            float relevant = predictor.predict(
                    new StringPair(query, "Steps to restart the payment service following an incident."))[0];
            float irrelevant = predictor.predict(
                    new StringPair(query, "The cafeteria menu changes every Monday."))[0];

            assertThat(relevant).isGreaterThan(irrelevant);
        }
    }
}
