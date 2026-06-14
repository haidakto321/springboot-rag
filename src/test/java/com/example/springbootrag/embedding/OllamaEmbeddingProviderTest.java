package com.example.springbootrag.embedding;

import com.example.springbootrag.config.EmbeddingProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class OllamaEmbeddingProviderTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void parsesEmbeddingFromOllamaResponse() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"embedding\":[0.1,0.2,0.3]}"));

        EmbeddingProperties props = new EmbeddingProperties();
        props.setDimension(3);
        RestClient client = RestClient.builder()
                .baseUrl(server.url("/").toString())
                .build();

        OllamaEmbeddingProvider provider = new OllamaEmbeddingProvider(client, props);
        float[] vec = provider.embed("hello");

        assertThat(vec).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(provider.dimension()).isEqualTo(3);
    }
}
