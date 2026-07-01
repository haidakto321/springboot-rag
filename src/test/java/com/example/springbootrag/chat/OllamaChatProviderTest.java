package com.example.springbootrag.chat;

import com.example.springbootrag.config.ChatProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OllamaChatProviderTest {

    private MockWebServer server;
    private OllamaChatProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        ChatProperties props = new ChatProperties();
        props.setModel("test-model");
        provider = new OllamaChatProvider(
                RestClient.builder().baseUrl(server.url("/").toString()).build(), props);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void sendsSystemAndUserMessagesAndReturnsReply() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"message": {"role": "assistant", "content": "the answer [1]"}}
                        """));

        String reply = provider.chat("you are helpful", "what is up?");

        assertThat(reply).isEqualTo("the answer [1]");
        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/chat");
        String body = req.getBody().readUtf8();
        assertThat(body).contains("\"model\":\"test-model\"");
        assertThat(body).contains("\"stream\":false");
        assertThat(body).contains("you are helpful");
        assertThat(body).contains("what is up?");
    }

    @Test
    void missingMessageBecomesChatUnavailable() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{}"));

        assertThatThrownBy(() -> provider.chat("s", "u"))
                .isInstanceOf(ChatUnavailableException.class);
    }

    @Test
    void connectionFailureBecomesChatUnavailable() throws Exception {
        server.shutdown(); // nothing listening anymore

        assertThatThrownBy(() -> provider.chat("s", "u"))
                .isInstanceOf(ChatUnavailableException.class);
    }

    @Test
    void httpErrorBecomesChatUnavailable() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("model crashed"));

        assertThatThrownBy(() -> provider.chat("s", "u"))
                .isInstanceOf(ChatUnavailableException.class);
    }
}
