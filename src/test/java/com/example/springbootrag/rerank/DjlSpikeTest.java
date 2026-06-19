package com.example.springbootrag.rerank;

// CONFIRMED working imports for DJL 0.30.0 (resolved against real jars in Task 1).
// NOTE: the plan's `CrossEncoderTranslatorFactory` does NOT exist in any DJL release.
// Real API: build a CrossEncoderTranslator from a HuggingFaceTokenizer and pass it
// via Criteria.optTranslator(...). StringPair lives in ai.djl.util, not modality.nlp.
// Task 4 (DjlReranker) reuses exactly this load path.
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.translator.CrossEncoderTranslator;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.util.StringPair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "RUN_DJL_SPIKE", matches = "true")
class DjlSpikeTest {

    @Test
    void crossEncoderScoresRelevantPairHigher() throws Exception {
        HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance("BAAI/bge-reranker-base");
        CrossEncoderTranslator translator = CrossEncoderTranslator.builder(tokenizer)
                .optSigmoid(true)
                .build();

        Criteria<StringPair, float[]> criteria = Criteria.builder()
                .setTypes(StringPair.class, float[].class)
                .optModelUrls("djl://ai.djl.huggingface.pytorch/BAAI/bge-reranker-base")
                .optEngine("PyTorch")
                .optTranslator(translator)
                .build();

        try (ZooModel<StringPair, float[]> model = criteria.loadModel();
             Predictor<StringPair, float[]> predictor = model.newPredictor()) {

            String query = "how to restart the payment service after an outage";
            float relevant = predictor.predict(
                    new StringPair(query, "Steps to restart the payment service following an incident."))[0];
            float irrelevant = predictor.predict(
                    new StringPair(query, "The cafeteria menu changes every Monday."))[0];

            assertThat(relevant).isGreaterThan(irrelevant);
        }
    }
}
