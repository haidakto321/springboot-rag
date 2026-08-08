package com.example.springbootrag.integration;

import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.repository.MetadataFilter;
import com.example.springbootrag.repository.ProjectRepository;
import com.example.springbootrag.repository.RecordCountRepository;
import com.example.springbootrag.security.SearchContext;
import com.example.springbootrag.security.TestContexts;
import com.example.springbootrag.service.RecordIngestService;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A count that ignores access labels leaks the existence of documents the caller may not read -
 * "you have 40 invoices" is information even when none of them can be opened. So the predicate is
 * the same one retrieval uses, and this test proves it against a real database.
 */
@SpringBootTest(properties = {"app.graph.edges=structural", "app.understand.facet-ttl-seconds=0"})
@Testcontainers
class RecordCountIntegrationTest {

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

    @Autowired RecordIngestService recordIngest;
    @Autowired ProjectRepository projectRepository;
    @Autowired RecordCountRepository counts;

    private static Long projectId;
    private static Long otherProjectId;

    @BeforeEach
    void seedOnce() throws Exception {
        if (projectId != null) return;
        long id = projectRepository.create("count-test", null);
        // Two readable ACME invoices, one GLOBEX, and one ACME invoice only 'hr' may read.
        recordIngest.ingest(id, record("INV-1", "ACME Corp", "open", null));
        recordIngest.ingest(id, record("INV-2", "ACME Corp", "overdue", null));
        recordIngest.ingest(id, record("INV-3", "GLOBEX Ltd", "open", null));
        recordIngest.ingest(id, record("INV-4", "ACME Corp", "open", List.of("hr")));
        long other = projectRepository.create("count-test-other", null);
        recordIngest.ingest(other, record("INV-9", "ACME Corp", "open", null));
        projectId = id;
        otherProjectId = other;
    }

    /** Long enough to render several chunks, so DISTINCT doc_id is doing visible work. */
    private static RecordRequest record(String docId, String customer, String status,
                                        List<String> groups) throws Exception {
        String json = """
                {"invoiceNumber":"%s","status":"%s","total":1000.5,
                 "customer":{"value":"%s","confidence":0.9},
                 "notes":"This invoice covers consulting delivered over the period. Payment terms are thirty days from the issue date, after which a late payment reminder is sent. Disputes must be raised in writing within ten working days of receipt, and any credit note issued references this invoice number."}
                """.formatted(docId, status, customer);
        return new RecordRequest(docId, "invoice", M.readTree(json), null, groups, null);
    }

    @Test
    void countsRecordsNotChunks() {
        long n = counts.count(TestContexts.PUBLIC, List.of(projectId), MetadataFilter.none());

        // 4 ingested into this project, one of which the public caller may not read.
        assertThat(n).isEqualTo(3);
    }

    @Test
    void appliesTheMetadataFilter() {
        MetadataFilter acme = MetadataFilter.parse("""
                {"docType":"invoice","filters":[{"path":"values.customer","op":"eq","value":"ACME Corp"}]}""");

        assertThat(counts.count(TestContexts.PUBLIC, List.of(projectId), acme)).isEqualTo(2);
    }

    @Test
    void aRecordTheCallerMayNotReadIsNotEvenCounted() {
        SearchContext hr = SearchContext.of("hr-user",
                Set.of(TestContexts.PUBLIC_GROUP, "hr"));
        MetadataFilter acme = MetadataFilter.parse("""
                {"docType":"invoice","filters":[{"path":"values.customer","op":"eq","value":"ACME Corp"}]}""");

        assertThat(counts.count(hr, List.of(projectId), acme)).isEqualTo(3);
        assertThat(counts.count(TestContexts.NOBODY, List.of(projectId), acme)).isZero();
    }

    @Test
    void respectsProjectScope() {
        MetadataFilter acme = MetadataFilter.parse("""
                {"filters":[{"path":"values.customer","op":"eq","value":"ACME Corp"}]}""");

        assertThat(counts.count(TestContexts.PUBLIC, List.of(otherProjectId), acme)).isEqualTo(1);
        assertThat(counts.count(TestContexts.PUBLIC, List.of(projectId, otherProjectId), acme))
                .isEqualTo(3);
    }

    @Test
    void anEmptyProjectScopeCountsEverythingReadable() {
        assertThat(counts.count(TestContexts.PUBLIC, List.of(), MetadataFilter.none()))
                .isEqualTo(4);
    }
}
