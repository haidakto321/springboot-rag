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
        // think:true even for the non-streaming call: reasoning must arrive in its own field
        // instead of being dumped into content (LEARNINGS section 12).
        assertThat(body).contains("\"think\":true");
        assertThat(body).doesNotContain("/no_think");
        assertThat(body).contains("you are helpful");
        assertThat(body).contains("what is up?");
    }

    @Test
    void anExplicitModelOverridesTheConfiguredOne() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"message": {"role": "assistant", "content": "{}"}}
                        """));

        provider.chat("system", "user", "qwen3:1.7b");

        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"model\":\"qwen3:1.7b\"");
    }

    @Test
    void temperatureAndSeedAreSentUnderOptions() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"message": {"role": "assistant", "content": "{}"}}
                        """));

        provider.chat("system", "user", new ChatProvider.Options("m", 0.0, 42));

        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"options\"").contains("\"temperature\":0.0").contains("\"seed\":42");
    }

    @Test
    void thinkFalseAndAnOutputCapAreForwardedWhenTheCallerAsksForThem() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"message": {"role": "assistant", "content": "search"}}
                        """));

        provider.chat("system", "user", new ChatProvider.Options(null, 0.0, 42, false, 16));

        String body = server.takeRequest().getBody().readUtf8();
        // A one-word classification has nothing worth reasoning about, and on a reasoning model
        // those tokens ARE the latency.
        assertThat(body).contains("\"think\":false");
        assertThat(body).contains("\"num_predict\":16");
        assertThat(body).contains("\"temperature\":0.0");
        assertThat(body).contains("\"seed\":42");
    }

    @Test
    void aResponseSchemaIsSentAsOllamaFormat() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"message": {"role": "assistant", "content": "{\\"route\\":\\"search\\"}"}}
                        """));

        provider.chat("system", "user", new ChatProvider.Options(null, 0.0, 42, false, 16,
                java.util.Map.of("type", "object")));

        String body = server.takeRequest().getBody().readUtf8();
        // "format" is the constraint that makes a reasoning model answer instead of narrate.
        assertThat(body).contains("\"format\":{\"type\":\"object\"}");
    }

    @Test
    void aCallerWithNoOpinionStillGetsThinkTrueAndNoOutputCap() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"message": {"role": "assistant", "content": "hi"}}
                        """));

        provider.chat("system", "user");

        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"think\":true");
        assertThat(body).doesNotContain("num_predict");
    }

    @Test
    void noGenerationSettingsMeansNoOptionsBlockAtAll() throws Exception {
        // An ordinary answer must keep the model's own defaults, not silently get temperature 0.
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"message": {"role": "assistant", "content": "hi"}}
                        """));

        provider.chat("system", "user");

        assertThat(server.takeRequest().getBody().readUtf8()).doesNotContain("\"options\"");
    }

    @Test
    void aBlankModelFallsBackToTheConfiguredOne() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"message": {"role": "assistant", "content": "{}"}}
                        """));

        provider.chat("system", "user", "");

        assertThat(server.takeRequest().getBody().readUtf8()).contains("\"model\":\"test-model\"");
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
    void streamRoutesReasoningToReasoningChannelAndSetsThinkTrue() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/x-ndjson")
                .setBody("""
                        {"message":{"role":"assistant","content":"<think>let me "},"done":false}
                        {"message":{"role":"assistant","content":"reason</think>The real "},"done":false}
                        {"message":{"role":"assistant","content":"answer."},"done":true}
                        """));

        List<String> answer = new ArrayList<>();
        List<String> reasoning = new ArrayList<>();
        provider.chatStream("s", List.of(new ChatProvider.ChatMessage("user", "u")),
                true, answer::add, reasoning::add);

        assertThat(String.join("", answer)).isEqualTo("The real answer.");
        assertThat(String.join("", reasoning)).isEqualTo("let me reason");
        assertThat(server.takeRequest().getBody().readUtf8()).contains("\"think\":true");
    }

    @Test
    void streamRoutesThinkingFieldToReasoning() throws Exception {
        // Modern Ollama returns reasoning in a separate "thinking" field, content empty meanwhile.
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/x-ndjson")
                .setBody("""
                        {"message":{"role":"assistant","content":"","thinking":"Let me "},"done":false}
                        {"message":{"role":"assistant","content":"","thinking":"think."},"done":false}
                        {"message":{"role":"assistant","content":"Answer "},"done":false}
                        {"message":{"role":"assistant","content":"here."},"done":true}
                        """));

        List<String> answer = new ArrayList<>();
        List<String> reasoning = new ArrayList<>();
        provider.chatStream("s", List.of(new ChatProvider.ChatMessage("user", "u")),
                true, answer::add, reasoning::add);

        assertThat(String.join("", answer)).isEqualTo("Answer here.");
        assertThat(String.join("", reasoning)).isEqualTo("Let me think.");
    }

    @Test
    void streamCapturesDanglingThinkCloseAsReasoning() throws Exception {
        // Some small models leak chain-of-thought with a closing </think> but no opening tag.
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/x-ndjson")
                .setBody("""
                        {"message":{"role":"assistant","content":"reasoning with no open tag</think>"},"done":false}
                        {"message":{"role":"assistant","content":"Clean answer."},"done":true}
                        """));

        List<String> answer = new ArrayList<>();
        List<String> reasoning = new ArrayList<>();
        provider.chatStream("s", List.of(new ChatProvider.ChatMessage("user", "u")),
                true, answer::add, reasoning::add);

        assertThat(String.join("", answer)).isEqualTo("Clean answer.");
        assertThat(String.join("", reasoning)).isEqualTo("reasoning with no open tag");
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
