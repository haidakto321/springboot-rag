package com.example.springbootrag.integration;

import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.repository.DocEdgeRepository;
import com.example.springbootrag.repository.MetadataFilter;
import com.example.springbootrag.repository.ProjectRepository;
import com.example.springbootrag.security.SearchContext;
import com.example.springbootrag.service.RecordIngestService;
import com.example.springbootrag.service.SearchService;
import com.example.springbootrag.web.dto.RecordRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The filter must be enforced INSIDE every backend query. These are the four ways that goes
 * wrong quietly: post-filtering, filtering after the reranker over-fetch trims, graph expansion
 * skipping the filter, and an empty filter degenerating into a predicate that matches nothing.
 */
@SpringBootTest(properties = "app.graph.edges=structural")
@Testcontainers
class MetadataFilterIntegrationTest {

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
    static class FakeEmbeddingConfig {
        @Bean
        @Primary
        EmbeddingProvider fakeEmbeddingProvider() {
            return new EmbeddingProvider() {
                @Override public float[] embed(String text) {
                    // Deterministic pseudo-vector: different texts get different directions, so
                    // vector search orders sensibly without needing a real model.
                    float[] v = new float[768];
                    int h = Math.abs(text.hashCode());
                    v[h % 768] = 1f;
                    v[0] = 0.5f;
                    return v;
                }
                @Override public int dimension() { return 768; }
            };
        }
    }

    @Autowired RecordIngestService recordIngest;
    @Autowired SearchService searchService;
    @Autowired ProjectRepository projectRepository;
    @Autowired DocEdgeRepository docEdges;

    private final SearchContext ctx = SearchContext.of("alice", Set.of("public"));
    private final SearchContext ctxNoAccess = SearchContext.of("bob", Set.of("nobody"));

    /**
     * One project per test. Sharing a project makes these tests lie to each other: the 60 decoys
     * of the over-fetch test otherwise fill every other test's unfiltered result window.
     */
    private long projectId;

    @BeforeEach
    void freshProject(TestInfo info) {
        projectId = projectRepository.create("filter-" + info.getTestMethod().orElseThrow().getName(), null);
    }

    private long projectId() {
        return projectId;
    }

    private void ingest(String docId, String customer, String text, List<String> groups,
                        Double confidence) {
        String conf = confidence == null ? "" : ",\"confidence\":" + confidence;
        String json = """
                {"customer":{"value":"%s"%s},"body":"%s"}""".formatted(customer, conf, text);
        try {
            recordIngest.ingest(projectId(), new RecordRequest(
                    docId, "invoice", M.readTree(json), null, groups, null));
        } catch (Exception e) {
            throw new IllegalStateException("seed failed for " + docId, e);
        }
    }

    private void ingestInvoice(String docId, String customer, String text) {
        ingest(docId, customer, text, List.of("public"), null);
    }

    private static MetadataFilter customerFilter(String customer) {
        return MetadataFilter.parse("""
                {"filters":[{"path":"values.customer","op":"eq","value":"%s"}]}"""
                .formatted(customer));
    }

    @Test
    void filterNarrowsResultsOnEveryBackend() {
        ingestInvoice("REC-A", "ACME", "late payment reminder");
        ingestInvoice("REC-B", "GLOBEX", "late payment reminder");

        MetadataFilter f = MetadataFilter.parse("""
                {"docType":"invoice",
                 "filters":[{"path":"values.customer","op":"eq","value":"ACME"}]}""");

        for (String type : List.of("fts", "pgvector", "qdrant", "hybrid", "rerank", "graph")) {
            List<SearchHit> hits = searchService.search(ctx, type, "late payment reminder", 10,
                    List.of(projectId()), List.of(), f);
            assertThat(hits).as("backend %s", type).isNotEmpty();
            assertThat(hits).as("backend %s", type)
                    .allMatch(h -> !h.docId().equals("REC-B"));
        }
    }

    @Test
    void pgvectorAndQdrantAgreeUnderTheSameFilter() {
        ingestInvoice("REC-C", "ACME", "unpaid balance notice");
        ingestInvoice("REC-D", "GLOBEX", "unpaid balance notice");

        MetadataFilter f = customerFilter("GLOBEX");
        List<String> pg = searchService.search(ctx, "pgvector", "unpaid balance", 10,
                        List.of(projectId()), List.of(), f)
                .stream().map(SearchHit::docId).distinct().sorted().toList();
        List<String> qd = searchService.search(ctx, "qdrant", "unpaid balance", 10,
                        List.of(projectId()), List.of(), f)
                .stream().map(SearchHit::docId).distinct().sorted().toList();

        assertThat(pg).containsExactlyElementsOf(qd);
        assertThat(pg).doesNotContain("REC-C");
    }

    @Test
    void filterAppliesBeforeTheRerankerOverFetchTrims() {
        // 60 decoys of the WRONG customer, more than app.rerank.candidates, plus one match. If
        // the filter ran after the over-fetch, the needle would be trimmed away first.
        for (int i = 0; i < 60; i++) {
            ingestInvoice("DECOY-" + i, "GLOBEX", "quarterly statement of account");
        }
        ingestInvoice("NEEDLE", "ACME", "quarterly statement of account");

        List<SearchHit> hits = searchService.search(ctx, "rerank", "quarterly statement", 10,
                List.of(projectId()), List.of(), customerFilter("ACME"));

        assertThat(hits).isNotEmpty();
        assertThat(hits).allMatch(h -> h.docId().equals("NEEDLE"));
    }

    @Test
    void graphExpansionCannotReturnANeighbourThatFailsTheFilter() {
        ingestInvoice("SEED", "ACME", "shipping terms and handover");
        ingestInvoice("NEIGHBOUR", "GLOBEX", "shipping terms appendix");
        docEdges.insertLink(projectId(), "SEED", "NEIGHBOUR");

        List<SearchHit> hits = searchService.search(ctx, "graph", "shipping terms", 10,
                List.of(projectId()), List.of(), customerFilter("ACME"));

        assertThat(hits).isNotEmpty();
        assertThat(hits).noneMatch(h -> h.docId().equals("NEIGHBOUR"));
    }

    @Test
    void emptyFilterReturnsEverythingReadable() {
        ingestInvoice("REC-E", "ACME", "delivery note enclosed");
        ingestInvoice("REC-F", "GLOBEX", "delivery note enclosed");

        List<SearchHit> hits = searchService.search(ctx, "hybrid", "delivery note enclosed", 20,
                List.of(projectId()), List.of(), MetadataFilter.none());

        assertThat(hits).extracting(SearchHit::docId).contains("REC-E", "REC-F");
    }

    @Test
    void filterCannotWidenPastAccessLabels() {
        ingestInvoice("VISIBLE", "ACME", "restricted terms of trade");

        // A caller whose groups match nothing gets nothing, filter or no filter.
        List<SearchHit> hits = searchService.search(ctxNoAccess, "hybrid", "restricted terms", 10,
                List.of(projectId()), List.of(), customerFilter("ACME"));

        assertThat(hits).isEmpty();
    }

    @Test
    void confidenceFilterNarrowsAndAbsenceOfItDoesNot() {
        ingest("LOW-CONF", "ACME", "payment schedule attached", List.of("public"), 0.30);
        ingest("HIGH-CONF", "ACME", "payment schedule attached", List.of("public"), 0.95);

        MetadataFilter trustworthy = MetadataFilter.parse("""
                {"filters":[{"path":"conf.min","op":"range","gte":0.7,"type":"number"}]}""");

        List<String> filtered = searchService.search(ctx, "pgvector", "payment schedule", 20,
                        List.of(projectId()), List.of(), trustworthy)
                .stream().map(SearchHit::docId).toList();
        assertThat(filtered).contains("HIGH-CONF").doesNotContain("LOW-CONF");

        // Low confidence is never unfindable - a threshold is the caller's policy, not the index's.
        List<String> unfiltered = searchService.search(ctx, "pgvector", "payment schedule", 20,
                        List.of(projectId()), List.of(), MetadataFilter.none())
                .stream().map(SearchHit::docId).toList();
        assertThat(unfiltered).contains("LOW-CONF", "HIGH-CONF");
    }

    @Test
    void dateRangeOnQdrantFailsLoudlyRatherThanSilently() {
        MetadataFilter f = MetadataFilter.parse("""
                {"filters":[{"path":"values.issueDate","op":"range",
                             "gte":"2026-01-01","type":"date"}]}""");

        assertThatThrownBy(() -> searchService.search(ctx, "qdrant", "anything", 10,
                List.of(projectId()), List.of(), f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("date range is not supported");
    }
}
