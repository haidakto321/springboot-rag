package com.example.springbootrag.integration;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.repository.ProjectRepository;
import com.example.springbootrag.security.TestContexts;
import com.example.springbootrag.service.AskService;
import com.example.springbootrag.service.RecordIngestService;
import com.example.springbootrag.web.dto.AskResponse;
import com.example.springbootrag.web.dto.RecordRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three routes, end to end, with a scripted model so the assertions are about routing rather
 * than about what a local model felt like saying.
 */
@SpringBootTest(properties = {"app.graph.edges=structural", "app.understand.facet-ttl-seconds=0"})
@Testcontainers
class RoutedAnswerIntegrationTest {

    private static final ObjectMapper M = new ObjectMapper();

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

    /** Answers by looking at which prompt it was handed. */
    @TestConfiguration
    static class ScriptedModelConfig {
        @Bean @Primary
        ChatProvider scriptedChat() {
            return new ChatProvider() {
                @Override public String chat(String systemPrompt, String userPrompt) {
                    return chat(systemPrompt, userPrompt, new Options(null, null, null));
                }

                @Override public String chat(String systemPrompt, String userPrompt, Options options) {
                    if (systemPrompt.startsWith("Classify the user's message")) {
                        if (userPrompt.toLowerCase().startsWith("how many")) return "aggregate";
                        return userPrompt.equalsIgnoreCase("what can you do") ? "chitchat" : "search";
                    }
                    if (systemPrompt.startsWith("You convert a user's question")) {
                        return """
                               {"docType":"invoice","filters":[
                                 {"path":"values.customer","op":"eq","value":"ACME Corp"}]}""";
                    }
                    return "ACME invoices are billed monthly [1]";
                }
            };
        }

        @Bean @Primary
        EmbeddingProvider fakeEmbeddingProvider() {
            return new EmbeddingProvider() {
                @Override public float[] embed(String text) {
                    float[] v = new float[768];
                    v[0] = 1f;
                    return v;
                }

                @Override public int dimension() { return 768; }
            };
        }
    }

    @Autowired AskService askService;
    @Autowired RecordIngestService recordIngest;
    @Autowired ProjectRepository projectRepository;

    private static Long projectId;

    @BeforeEach
    void seedOnce() throws Exception {
        if (projectId != null) return;
        long id = projectRepository.create("routing-test", null);
        for (int i = 1; i <= 3; i++) {
            String json = """
                    {"invoiceNumber":"INV-%d","status":"open","total":1000.5,
                     "customer":{"value":"ACME Corp","confidence":0.9},
                     "notes":"ACME invoices are billed monthly and payment is due in 30 days."}
                    """.formatted(i);
            recordIngest.ingest(id, new RecordRequest("INV-" + i, "invoice", M.readTree(json),
                    null, null, null));
        }
        projectId = id;
    }

    @Test
    void aGreetingIsAnsweredWithoutRetrievalOrAModelCall() {
        AskResponse r = askService.ask(TestContexts.PUBLIC, "hi", List.of(projectId));

        assertThat(r.route()).isEqualTo("chitchat");
        assertThat(r.sources()).isEmpty();
        assertThat(r.answer()).isEqualTo(
                com.example.springbootrag.service.AggregateAnswerer.CHITCHAT_REPLY);
    }

    @Test
    void aMetaQuestionTheRulesDoNotKnowIsStillRoutedToChitchat() {
        AskResponse r = askService.ask(TestContexts.PUBLIC, "what can you do", List.of(projectId));

        assertThat(r.route()).isEqualTo("chitchat");
        assertThat(r.sources()).isEmpty();
    }

    @Test
    void aCountingQuestionIsAnsweredWithACount() {
        AskResponse r = askService.ask(TestContexts.PUBLIC,
                "how many invoices for ACME Corp", List.of(projectId));

        assertThat(r.route()).isEqualTo("aggregate");
        assertThat(r.answer()).startsWith("3 invoice records match");
        assertThat(r.sources()).isEmpty();
    }

    @Test
    void anOrdinaryQuestionStillTakesTheSearchPath() {
        AskResponse r = askService.ask(TestContexts.PUBLIC,
                "when are ACME invoices billed", List.of(projectId));

        assertThat(r.route()).isEqualTo("search");
        assertThat(r.sources()).isNotEmpty();
        assertThat(r.answer()).contains("[1]");
    }

    @Test
    void anExplicitCallerFilterSkipsRoutingEntirely() {
        // A caller who supplied a filter has already said what they want narrowed; re-deciding the
        // shape of their request would silently ignore it.
        AskResponse r = askService.ask(TestContexts.PUBLIC, "how many invoices for ACME Corp",
                List.of(projectId),
                com.example.springbootrag.repository.MetadataFilter.parse("""
                        {"docType":"invoice"}"""));

        assertThat(r.route()).isEqualTo("search");
        assertThat(r.sources()).isNotEmpty();
    }
}
