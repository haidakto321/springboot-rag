package com.example.springbootrag.rerank;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.translator.CrossEncoderTranslator;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.util.StringPair;
import com.example.springbootrag.config.RerankProperties;
import com.example.springbootrag.model.SearchHit;

import java.util.Comparator;
import java.util.List;

/**
 * Real cross-encoder reranker (bge-reranker via DJL/PyTorch). Loads the model lazily on first use.
 *
 * <p>DJL 0.30.0 has no {@code CrossEncoderTranslatorFactory} (despite older docs); the cross-encoder
 * translator is built manually from a {@link HuggingFaceTokenizer} and supplied via
 * {@code Criteria.optTranslator(...)}. {@link StringPair} lives in {@code ai.djl.util}.
 */
public class DjlReranker implements Reranker {

    private final RerankProperties props;
    private volatile ZooModel<StringPair, float[]> model;

    public DjlReranker(RerankProperties props) {
        this.props = props;
    }

    private ZooModel<StringPair, float[]> model() {
        if (model == null) {
            synchronized (this) {
                if (model == null) {
                    model = loadModel();
                }
            }
        }
        return model;
    }

    private ZooModel<StringPair, float[]> loadModel() {
        try {
            HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(props.getModel());
            CrossEncoderTranslator translator = CrossEncoderTranslator.builder(tokenizer)
                    .optSigmoid(true)
                    .build();
            Criteria<StringPair, float[]> criteria = Criteria.builder()
                    .setTypes(StringPair.class, float[].class)
                    .optModelUrls("djl://ai.djl.huggingface.pytorch/" + props.getModel())
                    .optEngine("PyTorch")
                    .optTranslator(translator)
                    .build();
            return criteria.loadModel();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load reranker model: " + props.getModel(), e);
        }
    }

    @Override
    public List<SearchHit> rerank(String query, List<SearchHit> candidates, int topK) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        try (Predictor<StringPair, float[]> predictor = model().newPredictor()) {
            record Scored(SearchHit hit, double score) {}
            return candidates.stream()
                    .map(h -> {
                        try {
                            double s = predictor.predict(new StringPair(query, h.content()))[0];
                            return new Scored(h, s);
                        } catch (Exception e) {
                            throw new IllegalStateException("Rerank scoring failed", e);
                        }
                    })
                    .sorted(Comparator.comparingDouble(Scored::score).reversed())
                    .limit(topK)
                    .map(s -> new SearchHit(s.hit().id(), s.hit().docId(),
                            s.hit().chunkIndex(), s.hit().content(), s.score()))
                    .toList();
        }
    }
}
