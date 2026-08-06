package com.example.springbootrag.integration;

import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.repository.DocEdgeRepository;
import com.example.springbootrag.repository.DocumentRegistry;
import com.example.springbootrag.repository.QdrantRepository;
import com.example.springbootrag.security.SearchContext;
import com.example.springbootrag.service.IngestService;
import com.example.springbootrag.service.ProjectService;
import com.example.springbootrag.service.RecordIngestService;
import com.example.springbootrag.web.dto.RecordRequest;
import com.example.springbootrag.web.dto.RecordResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.qdrant.QdrantContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Record ingest: the three outcomes (indexed / metadata-refreshed / skipped) and delete depth. */
@SpringBootTest(properties = "app.graph.edges=structural")
@Testcontainers
class RecordIngestIntegrationTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static final String INVOICE_JSON = """
            {"invoiceNumber":"INV-5575",
             "customer":{"value":"ACME Corp","confidence":0.82,
                         "grounding":{"page":2,"bbox":[12,44,90,60]}},
             "lineItems":[{"sku":"A-1","description":"Widget assembly"},
                          {"sku":"B-2","description":"Gadget housing"}]}""";

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

    /** Counts calls so "no embedding happened" is an assertion rather than a hope. */
    static class CountingEmbeddingProvider implements EmbeddingProvider {
        final AtomicInteger calls = new AtomicInteger();

        @Override public float[] embed(String text) {
            calls.incrementAndGet();
            float[] v = new float[768];
            v[0] = 1f;
            return v;
        }

        @Override public int dimension() { return 768; }
    }

    @TestConfiguration
    static class FakeEmbeddingConfig {
        @Bean
        @Primary
        EmbeddingProvider fakeEmbeddingProvider() {
            return new CountingEmbeddingProvider();
        }
    }

    @Autowired RecordIngestService recordIngest;
    @Autowired IngestService ingestService;
    @Autowired ProjectService projectService;
    @Autowired DocumentRegistry registry;
    @Autowired DocEdgeRepository docEdges;
    @Autowired QdrantRepository qdrantRepo;
    @Autowired EmbeddingProvider embeddings;
    @Autowired JdbcTemplate jdbc;

    private int embedCalls() {
        return ((CountingEmbeddingProvider) embeddings).calls.get();
    }

    private RecordRequest request(String docId, String json) {
        try {
            JsonNode node = M.readTree(json);
            return new RecordRequest(docId, "invoice", node, null, List.of("public"), null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void firstIngestStoresChunksAndRegistryRow() {
        long projectId = projectService.defaultProjectId();

        RecordResponse res = recordIngest.ingest(projectId, request("REC-1", INVOICE_JSON));

        assertThat(res.status()).isEqualTo("indexed");
        assertThat(res.chunksStored()).isGreaterThan(0);
        assertThat(registry.find(projectId, "REC-1")).isPresent();

        // Provenance is metadata, never embedded text.
        String content = jdbc.queryForObject(
                "SELECT string_agg(content, ' ') FROM chunks WHERE doc_id = 'REC-1'", String.class);
        assertThat(content).contains("ACME Corp").doesNotContain("0.82").doesNotContain("bbox");

        Double confidence = jdbc.queryForObject(
                "SELECT (metadata->'prov'->'customer'->>'confidence')::float FROM chunks " +
                        "WHERE doc_id = 'REC-1' AND (metadata->'prov'->'customer') IS NOT NULL LIMIT 1",
                Double.class);
        assertThat(confidence).isEqualTo(0.82);
    }

    @Test
    void reIngestingTheSameRecordSkipsWithoutEmbedding() {
        long projectId = projectService.defaultProjectId();
        recordIngest.ingest(projectId, request("REC-2", INVOICE_JSON));
        int before = embedCalls();

        RecordResponse res = recordIngest.ingest(projectId, request("REC-2", INVOICE_JSON));

        assertThat(res.status()).isEqualTo("skipped");
        assertThat(embedCalls()).isEqualTo(before);
    }

    @Test
    void confidenceOnlyChangeRefreshesMetadataWithoutEmbedding() {
        long projectId = projectService.defaultProjectId();
        recordIngest.ingest(projectId, request("REC-3", """
                {"customer":{"value":"ACME","confidence":0.82}}"""));
        int before = embedCalls();

        RecordResponse res = recordIngest.ingest(projectId, request("REC-3", """
                {"customer":{"value":"ACME","confidence":0.93}}"""));

        assertThat(res.status()).isEqualTo("metadata-refreshed");
        assertThat(embedCalls()).isEqualTo(before);

        Double stored = jdbc.queryForObject(
                "SELECT (metadata->'prov'->'customer'->>'confidence')::float FROM chunks " +
                        "WHERE doc_id = 'REC-3' LIMIT 1", Double.class);
        assertThat(stored).isEqualTo(0.93);
    }

    @Test
    void changedValueReIndexes() {
        long projectId = projectService.defaultProjectId();
        recordIngest.ingest(projectId, request("REC-4", """
                {"customer":{"value":"ACME"}}"""));

        RecordResponse res = recordIngest.ingest(projectId, request("REC-4", """
                {"customer":{"value":"OTHER"}}"""));

        assertThat(res.status()).isEqualTo("indexed");
        String content = jdbc.queryForObject(
                "SELECT string_agg(content, ' ') FROM chunks WHERE doc_id = 'REC-4'", String.class);
        assertThat(content).contains("OTHER").doesNotContain("ACME");
    }

    @Test
    void deleteRemovesChunksFromBothStoresAndTheRegistry() throws Exception {
        long projectId = projectService.defaultProjectId();
        recordIngest.ingest(projectId, request("REC-5", INVOICE_JSON));
        SearchContext ctx = SearchContext.of("alice", Set.of("public"));
        assertThat(qdrantRepo.search(ctx, probe(), 10, List.of(projectId), List.of("REC-5")))
                .isNotEmpty();

        ingestService.delete(projectId, "REC-5");

        Integer pgRows = jdbc.queryForObject(
                "SELECT count(*) FROM chunks WHERE project_id = ? AND doc_id = 'REC-5'",
                Integer.class, projectId);
        assertThat(pgRows).isZero();
        assertThat(registry.find(projectId, "REC-5")).isEmpty();
        // An ON DELETE CASCADE proves only the store it lives in is clean (LEARNINGS section 13).
        assertThat(qdrantRepo.search(ctx, probe(), 10, List.of(projectId), List.of("REC-5")))
                .isEmpty();
    }

    @Test
    void deleteAlsoRemovesInboundEdges() {
        long projectId = projectService.defaultProjectId();
        recordIngest.ingest(projectId, request("REC-6", INVOICE_JSON));
        docEdges.insertLink(projectId, "OTHER-DOC", "REC-6");

        ingestService.delete(projectId, "REC-6");

        // A dangling inbound edge lets graph expansion hop to a document that no longer exists.
        assertThat(docEdges.neighbors(projectId, List.of("OTHER-DOC"))).doesNotContain("REC-6");
    }

    @Test
    void emptyRenderIsRejected() {
        long projectId = projectService.defaultProjectId();

        assertThatThrownBy(() -> recordIngest.ingest(projectId, request("REC-7", """
                {"note":null,"comment":""}""")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no text");
    }

    @Test
    void docTypeIsRequired() {
        long projectId = projectService.defaultProjectId();

        assertThatThrownBy(() -> {
            JsonNode node = M.readTree("""
                    {"a":"b"}""");
            recordIngest.ingest(projectId,
                    new RecordRequest("REC-8", null, node, null, List.of("public"), null));
        }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("docType");
    }

    private static float[] probe() {
        float[] v = new float[768];
        v[0] = 1f;
        return v;
    }
}
