package com.example.springbootrag.integration;

import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.repository.ProjectRepository;
import com.example.springbootrag.security.SearchContext;
import com.example.springbootrag.security.TestContexts;
import com.example.springbootrag.service.RecordIngestService;
import com.example.springbootrag.understand.Facet;
import com.example.springbootrag.understand.FacetCatalogue;
import com.example.springbootrag.web.dto.RecordRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * The catalogue is derived from indexed metadata, so it is only meaningful against a real database.
 *
 * <p>The TTL is set to zero so each test reads the database rather than a neighbour's cached
 * answer - caching is what makes a facet test lie about the data it claims to describe.
 */
@SpringBootTest(properties = {"app.graph.edges=structural", "app.understand.facet-ttl-seconds=0"})
@Testcontainers
class FacetCatalogueIntegrationTest {

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
    @Autowired FacetCatalogue catalogue;

    @Test
    void facetsAreDerivedFromIndexedRecords() throws Exception {
        long projectId = projectRepository.create("facets-test", null);
        recordIngest.ingest(projectId, invoice("INV-1", "ACME Corp", 0.82));
        recordIngest.ingest(projectId, invoice("INV-2", "GLOBEX Ltd", 0.44));

        List<Facet> facets = catalogue.forProjects(TestContexts.PUBLIC, List.of(projectId));

        assertThat(facets).extracting(Facet::path)
                .contains("values.customer", "values.invoiceNumber", "conf.min");
        Facet customer = facets.stream().filter(f -> f.path().equals("values.customer"))
                .findFirst().orElseThrow();
        assertThat(customer.docType()).isEqualTo("invoice");
        assertThat(customer.samples()).contains("ACME Corp", "GLOBEX Ltd");
        assertThat(customer.distinctCount()).isEqualTo(2);

        Facet conf = facets.stream().filter(f -> f.path().equals("conf.min")).findFirst().orElseThrow();
        assertThat(conf.type()).isEqualTo("number");
    }

    @Test
    void provenancePathsAreNotOffered() throws Exception {
        long projectId = projectRepository.create("facets-prov", null);
        recordIngest.ingest(projectId, invoice("INV-3", "ACME Corp", 0.82));

        assertThat(catalogue.forProjects(TestContexts.PUBLIC, List.of(projectId)))
                .extracting(Facet::path)
                .noneMatch(p -> p.startsWith("prov."));
    }

    @Test
    void aCallerWithoutTheGroupSeesNoFacets() throws Exception {
        long projectId = projectRepository.create("facets-acl", null);
        recordIngest.ingest(projectId, invoice("INV-4", "ACME Corp", 0.82));

        SearchContext outsider = SearchContext.of("bob", java.util.Set.of("nobody"));
        assertThat(catalogue.forProjects(outsider, List.of(projectId))).isEmpty();
    }

    private RecordRequest invoice(String docId, String customer, double confidence) throws Exception {
        String json = """
                {"invoiceNumber":"%s","issueDate":"2026-05-02",
                 "customer":{"value":"%s","confidence":%s}}""".formatted(docId, customer, confidence);
        return new RecordRequest(docId, "invoice", M.readTree(json),
                null, List.of("public"), null);
    }
}
