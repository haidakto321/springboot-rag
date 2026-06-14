package com.example.springbootrag.embedding;

import com.example.springbootrag.config.EmbeddingProperties;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

public class OllamaEmbeddingProvider implements EmbeddingProvider {

    private final RestClient client;
    private final EmbeddingProperties props;

    public OllamaEmbeddingProvider(RestClient client, EmbeddingProperties props) {
        this.client = client;
        this.props = props;
    }

    @Override
    public float[] embed(String text) {
        OllamaResponse resp = client.post()
                .uri("/api/embeddings")
                .body(Map.of("model", props.getModel(), "prompt", text))
                .retrieve()
                .body(OllamaResponse.class);

        if (resp == null || resp.embedding() == null) {
            throw new IllegalStateException("Ollama returned no embedding");
        }
        List<Double> e = resp.embedding();
        float[] out = new float[e.size()];
        for (int i = 0; i < e.size(); i++) {
            out[i] = e.get(i).floatValue();
        }
        return out;
    }

    @Override
    public int dimension() {
        return props.getDimension();
    }

    private record OllamaResponse(List<Double> embedding) {}
}
