package com.example.springbootrag.chat;

import com.example.springbootrag.config.ChatProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

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
        assertThat(body).contains("\"think\":false");
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

    @Test
    void streamsTokenDeltasInOrderAndSendsStreamTrue() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/x-ndjson")
                .setBody("""
                        {"message":{"role":"assistant","content":"Hello"},"done":false}
                        {"message":{"role":"assistant","content":" world"},"done":false}
                        {"message":{"role":"assistant","content":"!"},"done":true}
                        """));

        List<String> tokens = new ArrayList<>();
        provider.chatStream("you are helpful",
                List.of(new ChatProvider.ChatMessage("user", "hi")), tokens::add);

        // ThinkFilter may re-chunk token boundaries; assert the assembled text.
        assertThat(String.join("", tokens)).isEqualTo("Hello world!");
        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"stream\":true");
        assertThat(body).contains("\"role\":\"system\"");
        assertThat(body).contains("\"role\":\"user\"");
    }

    @Test
    void streamSuppressesThinkBlock() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/x-ndjson")
                .setBody("""
                        {"message":{"role":"assistant","content":"<think>let me "},"done":false}
                        {"message":{"role":"assistant","content":"reason about this</think>"},"done":false}
                        {"message":{"role":"assistant","content":"The real "},"done":false}
                        {"message":{"role":"assistant","content":"answer."},"done":true}
                        """));

        List<String> tokens = new ArrayList<>();
        provider.chatStream("s", List.of(new ChatProvider.ChatMessage("user", "u")), tokens::add);

        assertThat(String.join("", tokens)).isEqualTo("The real answer.");
    }

    @Test
    void chatStripsThinkBlock() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"message": {"role": "assistant", "content": "<think>reasoning here</think>\\n\\nFinal answer [1]"}}
                        """));

        assertThat(provider.chat("s", "u")).isEqualTo("Final answer [1]");
    }

    @Test
    void streamStopsAtDoneAndIgnoresUnknownFields() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/x-ndjson")
                .setBody("""
                        {"model":"m","created_at":"t","message":{"role":"assistant","content":"a"},"done":false}
                        {"message":{"role":"assistant","content":"b"},"done":true,"total_duration":123}
                        {"message":{"role":"assistant","content":"SHOULD-NOT-APPEAR"},"done":false}
                        """));

        List<String> tokens = new ArrayList<>();
        provider.chatStream("s", List.of(new ChatProvider.ChatMessage("user", "u")), tokens::add);

        assertThat(String.join("", tokens)).isEqualTo("ab");
    }

    @Test
    void streamHttpErrorBecomesChatUnavailable() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        assertThatThrownBy(() -> provider.chatStream(
                "s", List.of(new ChatProvider.ChatMessage("user", "u")), t -> {}))
                .isInstanceOf(ChatUnavailableException.class);
    }
}
