package com.example.springbootrag.integration;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.repository.MetadataFilter;
import com.example.springbootrag.repository.ProjectRepository;
import com.example.springbootrag.security.TestContexts;
import com.example.springbootrag.service.AskService;
import com.example.springbootrag.service.RecordIngestService;
import com.example.springbootrag.trace.RagTrace;
import com.example.springbootrag.trace.TraceRepository;
import com.example.springbootrag.web.dto.AskResponse;
import com.example.springbootrag.web.dto.RecordRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
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
 * Query understanding end to end on the /ask path: extract, apply, widen, and report.
 *
 * <p>The chat provider is stubbed rather than mocked away, because the behaviour under test is what
 * happens to a REAL model reply - including a hallucinated value that matches nothing.
 */
@SpringBootTest(properties = {"app.graph.edges=structural", "app.understand.facet-ttl-seconds=0"})
@Testcontainers
class QueryUnderstandingIntegrationTest {

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

    @TestConfiguration
    static class StubChatConfig {
        static volatile String extractionReply = "{}";
        static volatile boolean throwOnExtraction = false;

        @Bean @Primary
        ChatProvider stubChat() {
            return new ChatProvider() {
                @Override public String chat(String systemPrompt, String userPrompt) {
                    if (systemPrompt.contains("convert a user's question into a search filter")) {
                        if (throwOnExtraction) throw new IllegalStateException("model down");
                        return extractionReply;
                    }
                    return "The answer is here [1]";
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
    @Autowired TraceRepository traceRepository;

    @AfterEach
    void resetStub() {
        StubChatConfig.extractionReply = "{}";
        StubChatConfig.throwOnExtraction = false;
    }

    @Test
    void anExtractedFilterNarrowsTheAnswerSources() {
        long projectId = seedTwoCustomers("qu-narrow");
        StubChatConfig.extractionReply = """
                {"docType":"invoice",
                 "filters":[{"path":"values.customer","op":"eq","value":"ACME Corp"}]}""";

        AskResponse res = askService.ask(TestContexts.PUBLIC, "late payment", List.of(projectId));

        assertThat(res.sources()).isNotEmpty();
        assertThat(res.sources()).allMatch(s -> s.docId().equals("INV-ACME"));
        assertThat(res.widened()).isFalse();
        assertThat(res.appliedFilter()).isNotNull();
    }

    @Test
    void aFilterThatMatchesNothingWidensAndSaysSo() {
        long projectId = seedTwoCustomers("qu-widen");
        StubChatConfig.extractionReply = """
                {"filters":[{"path":"values.customer","op":"eq","value":"NOBODY Ltd"}]}""";

        AskResponse res = askService.ask(TestContexts.PUBLIC, "late payment", List.of(projectId));

        // A mis-extracted value must not become a confident "I don't know".
        assertThat(res.widened()).isTrue();
        assertThat(res.sources()).isNotEmpty();
    }

    @Test
    void extractionFailureLeavesTheAnswerWorking() {
        long projectId = seedTwoCustomers("qu-fail");
        StubChatConfig.throwOnExtraction = true;

        AskResponse res = askService.ask(TestContexts.PUBLIC, "late payment", List.of(projectId));

        assertThat(res.sources()).isNotEmpty();
        assertThat(res.appliedFilter()).isNull();
    }

    @Test
    void anExplicitCallerFilterWinsOverExtraction() {
        long projectId = seedTwoCustomers("qu-explicit");
        StubChatConfig.extractionReply = """
                {"filters":[{"path":"values.customer","op":"eq","value":"ACME Corp"}]}""";

        MetadataFilter caller = MetadataFilter.parse("""
                {"filters":[{"path":"values.customer","op":"eq","value":"GLOBEX Ltd"}]}""");
        AskResponse res = askService.ask(TestContexts.PUBLIC, "late payment", List.of(projectId), caller);

        assertThat(res.sources()).allMatch(s -> s.docId().equals("INV-GLOBEX"));
    }

    @Test
    void theTraceCarriesTheFilterAndTheWidenDecision() {
        long projectId = seedTwoCustomers("qu-trace");
        StubChatConfig.extractionReply = """
                {"filters":[{"path":"values.customer","op":"eq","value":"NOBODY Ltd"}]}""";

        askService.ask(TestContexts.PUBLIC, "late payment", List.of(projectId));

        RagTrace trace = traceRepository.recent(TestContexts.PUBLIC.principal(), 1).getFirst();
        assertThat(trace.filterWidened()).isTrue();
        assertThat(trace.appliedFilter()).contains("NOBODY Ltd");
        assertThat(trace.stageLatencyMs()).containsKey("understand");
    }

    /** Two invoices with the same body text, so only the metadata filter can tell them apart. */
    private long seedTwoCustomers(String projectName) {
        long projectId = projectRepository.create(projectName, null);
        recordIngest.ingest(projectId, invoice("INV-ACME", "ACME Corp"));
        recordIngest.ingest(projectId, invoice("INV-GLOBEX", "GLOBEX Ltd"));
        return projectId;
    }

    private RecordRequest invoice(String docId, String customer) {
        try {
            String json = """
                    {"invoiceNumber":"%s","issueDate":"2026-05-02",
                     "notes":"payment is late, reminder sent",
                     "customer":{"value":"%s","confidence":0.82}}""".formatted(docId, customer);
            return new RecordRequest(docId, "invoice", M.readTree(json),
                    null, List.of("public"), null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
