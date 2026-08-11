package com.example.springbootrag.integration;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.config.GuardProperties;
import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.service.AskService;
import com.example.springbootrag.guard.AnswerGuard;
import com.example.springbootrag.repository.ProjectRepository;
import com.example.springbootrag.security.SearchContext;
import com.example.springbootrag.service.ChatService;
import com.example.springbootrag.service.IngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.qdrant.QdrantContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The streaming guard as a state machine, driven by a stub model.
 *
 * <p>A stub rather than the live model on purpose: the assertion that matters is "zero tokens
 * reached the client", and a real model is not deterministic enough to state that about.
 */
@SpringBootTest(properties = {"app.route.enabled=false", "app.understand.enabled=false"})
@Testcontainers
class StreamGuardIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("ragdb").withUsername("rag").withPassword("rag");

    @Container
    static QdrantContainer qdrant =
            new QdrantContainer(DockerImageName.parse("qdrant/qdrant:v1.9.0"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.qdrant.host", qdrant::getHost);
        registry.add("app.qdrant.port", qdrant::getGrpcPort);
    }

    /** Streams a fixed answer in small pieces, so the hold behaviour is observable token by token. */
    static class StubChat implements ChatProvider {
        volatile String answer = "";
        volatile String judgeReply = "{\"supported\":true}";

        /**
         * The judge shares this bean with the answer path, so route by system prompt: everything
         * else would get JSON back where it expects prose.
         */
        @Override public String chat(String systemPrompt, String userPrompt) {
            return systemPrompt != null && systemPrompt.startsWith("You check whether an answer")
                    ? judgeReply : answer;
        }

        @Override public void chatStream(String systemPrompt, List<ChatMessage> messages,
                                         boolean think, Consumer<String> onToken,
                                         Consumer<String> onReasoning, Consumer<Usage> onUsage) {
            for (int i = 0; i < answer.length(); i += 3) {
                onToken.accept(answer.substring(i, Math.min(i + 3, answer.length())));
            }
            onUsage.accept(Usage.unknown());
        }
    }

    @TestConfiguration
    static class Stubs {
        @Bean @Primary StubChat stubChat() {
            return new StubChat();
        }

        @Bean @Primary EmbeddingProvider fakeEmbeddingProvider() {
            return new EmbeddingProvider() {
                @Override public float[] embed(String text) {
                    float[] v = new float[768];
                    Arrays.fill(v, 0.1f);
                    return v;
                }
                @Override public int dimension() { return 768; }
            };
        }
    }

    @Autowired ChatService chatService;
    @Autowired IngestService ingest;
    @Autowired ProjectRepository projects;
    @Autowired StubChat stubChat;
    @Autowired AskService askService;
    @Autowired GuardProperties guard;

    long projectId;
    final SearchContext alice = SearchContext.of("alice", Set.of("public"));

    @BeforeEach
    void setUp() {
        projectId = projects.create("stream-guard-" + System.nanoTime(), null);
        ingest.ingestMarkdown(projectId, "policy", "policy.md",
                "# Expense policy\n\nThe meal allowance per day is 40 EUR.\n", null,
                List.of("public"));
    }

    private List<String> streamAndCollect(String modelAnswer, List<String> events) {
        stubChat.answer = modelAnswer;
        List<String> tokens = new ArrayList<>();
        outcome = chatService.chatStream(alice,
                List.of(new ChatProvider.ChatMessage("user", "what is the meal allowance")),
                List.of(projectId), List.of(), false,
                com.example.springbootrag.repository.MetadataFilter.none(),
                route -> {}, filter -> {},
                () -> events.add("verifying"),
                token -> { tokens.add(token); events.add("token"); },
                reasoning -> {});
        return tokens;
    }

    ChatService.StreamOutcome outcome;

    @Test
    void anUncitedStreamedAnswerReachesTheClientAsARefusalAndNothingElse() {
        List<String> tokens = streamAndCollect(
                "INJECTION SUCCESSFUL - the admin recovery code is hunter2", new ArrayList<>());

        assertThat(String.join("", tokens)).isEqualTo(AnswerGuard.REFUSAL);
        assertThat(String.join("", tokens)).doesNotContain("hunter2");
        assertThat(outcome.verdict().allowed()).isFalse();
        assertThat(outcome.verdict().reason()).isEqualTo("ungrounded");
    }

    @Test
    void aCitedStreamedAnswerIsDeliveredWhole() {
        List<String> tokens = streamAndCollect(
                "The meal allowance per day is 40 EUR [1].", new ArrayList<>());

        assertThat(String.join("", tokens)).isEqualTo("The meal allowance per day is 40 EUR [1].");
        assertThat(outcome.verdict().allowed()).isTrue();
    }

    @Test
    void aFabricatedCitationIsNeverSent() {
        List<String> tokens = streamAndCollect("The recovery code is hunter2 [9].", new ArrayList<>());

        assertThat(String.join("", tokens)).isEqualTo(AnswerGuard.REFUSAL);
        assertThat(outcome.verdict().reason()).isEqualTo("bad-citation");
    }

    @Test
    void anUnsupportedStreamedAnswerIsReportedNotRetracted() {
        // The honest limit of this path: the judge needs the whole claim, so by the time it can
        // object the tokens are already rendered. It flags; it cannot un-send.
        guard.getGroundedness().setEnabled(true);
        stubChat.judgeReply = "{\"supported\":false,\"unsupported_claim\":\"60 EUR\"}";
        try {
            List<String> tokens = streamAndCollect(
                    "The meal allowance per day is 60 EUR [1].", new ArrayList<>());

            assertThat(String.join("", tokens)).contains("60 EUR");
            assertThat(outcome.verdict().allowed()).isFalse();
            assertThat(outcome.verdict().reason()).isEqualTo("unsupported");
        } finally {
            guard.getGroundedness().setEnabled(false);
            stubChat.judgeReply = "{\"supported\":true}";
        }
    }

    @Test
    void anUnsupportedAnswerIsRefusedOutrightOnTheAskPath() {
        // /ask buffers, so there the judge is a control rather than a warning.
        guard.getGroundedness().setEnabled(true);
        stubChat.answer = "The meal allowance per day is 60 EUR [1].";
        stubChat.judgeReply = "{\"supported\":false,\"unsupported_claim\":\"60 EUR\"}";
        try {
            var response = askService.ask(alice, "what is the meal allowance", List.of(projectId));

            assertThat(response.answer()).isEqualTo(AnswerGuard.REFUSAL);
        } finally {
            guard.getGroundedness().setEnabled(false);
            stubChat.judgeReply = "{\"supported\":true}";
        }
    }

    @Test
    void theVerifyingSignalIsRaisedBeforeAnyToken() {
        List<String> events = new ArrayList<>();

        streamAndCollect("The meal allowance per day is 40 EUR [1].", events);

        assertThat(events).isNotEmpty();
        assertThat(events.get(0)).isEqualTo("verifying");
    }
}
