package com.example.springbootrag.integration;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.model.DocumentSummary;
import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.repository.FeedbackRepository;
import com.example.springbootrag.repository.PgVectorRepository;
import com.example.springbootrag.repository.ProjectRepository;
import com.example.springbootrag.security.SearchContext;
import com.example.springbootrag.security.TestContexts;
import com.example.springbootrag.service.AskService;
import com.example.springbootrag.service.IngestService;
import com.example.springbootrag.service.SearchService;
import com.example.springbootrag.web.dto.ChunkView;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The "now try to break it" half of RAG-MASTERY section 1.
 *
 * <p>Two users in different groups and one restricted document, then every route a restricted
 * chunk could escape through: each of the six backends, browser-supplied scope parameters, the
 * reranker's over-fetch, graph expansion, document listings, the chunk view, the answer path, and
 * the feedback store.
 *
 * <p>Reads use fake embeddings, so "does semantic search rank it well" is not the subject here -
 * the subject is whether a row the caller may not read can appear at all.
 */
@SpringBootTest
@Testcontainers
class AccessControlIntegrationTest {

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
        // Both documents must be retrievable by content, not by vector distance.
        registry.add("app.graph.edges", () -> "structural");
    }

    /** Word-bucket fake embedding: deterministic, no Ollama, and similar text lands nearby. */
    @TestConfiguration
    static class FakeEmbeddingConfig {
        @Bean
        @Primary
        EmbeddingProvider fakeEmbeddingProvider() {
            return new EmbeddingProvider() {
                @Override public float[] embed(String text) {
                    float[] v = new float[768];
                    for (String w : text.toLowerCase().split("\\W+")) {
                        if (!w.isBlank()) v[Math.abs(w.hashCode()) % 768] += 1f;
                    }
                    return v;
                }
                @Override public int dimension() { return 768; }
            };
        }

        /** No Ollama in this test: the question is which chunks reach the prompt, not what a model says. */
        @Bean
        @Primary
        ChatProvider fakeChatProvider() {
            return (system, user) -> "canned answer [1]";
        }
    }

    static final String PUBLIC_DOC = "Onboarding-Guide";
    static final String SECRET_DOC = "Salary-Bands-2026";
    static final String SHARED_TERM = "compensation";

    /** alice is in hr and can read the restricted document; bob is not. */
    static final SearchContext ALICE = SearchContext.of("alice", Set.of("public", "hr"));
    static final SearchContext BOB = SearchContext.of("bob", Set.of("public", "eng"));

    @Autowired IngestService ingest;
    @Autowired SearchService search;
    @Autowired AskService askService;
    @Autowired PgVectorRepository pgVector;
    @Autowired ProjectRepository projects;
    @Autowired FeedbackRepository feedback;
    @Autowired JdbcTemplate jdbc;

    /** Ingested once for the whole class: embedding 4 chunks per test would only be slower. */
    static long projectId = -1;

    long project() {
        if (projectId > 0) return projectId;
        jdbc.update("DELETE FROM chunk_feedback");
        jdbc.update("DELETE FROM projects WHERE name = 'ACL'");
        projectId = projects.create("ACL", null);

        ingest.ingestMarkdown(projectId, PUBLIC_DOC, PUBLIC_DOC + ".md",
                "# Onboarding\n\nYour first week covers laptops, badges and " + SHARED_TERM + " questions.",
                null, List.of("public"));
        ingest.ingestMarkdown(projectId, SECRET_DOC, SECRET_DOC + ".md",
                "# Salary bands\n\nBand 5 " + SHARED_TERM + " is 120000 per year.",
                null, List.of("hr"));
        return projectId;
    }

    private static List<String> docsOf(List<SearchHit> hits) {
        return hits.stream().map(SearchHit::docId).distinct().toList();
    }

    @Test
    void everyBackendHidesTheRestrictedDocumentFromTheWrongGroup() {
        long p = project();
        for (String backend : List.of("fts", "pgvector", "qdrant", "hybrid", "rerank", "graph")) {
            List<SearchHit> aliceHits = search.search(ALICE, backend, SHARED_TERM, 10, List.of(p), List.of());
            List<SearchHit> bobHits = search.search(BOB, backend, SHARED_TERM, 10, List.of(p), List.of());

            assertThat(docsOf(bobHits))
                    .as("backend '%s' leaked the restricted document to a user outside its group", backend)
                    .doesNotContain(SECRET_DOC);
            assertThat(docsOf(aliceHits))
                    .as("backend '%s' hid the restricted document from a user INSIDE its group - "
                            + "a filter that denies everyone proves nothing", backend)
                    .contains(SECRET_DOC);
        }
    }

    @Test
    void craftedDocIdScopeCannotWidenAccess() {
        long p = project();
        // The browser asks explicitly for the restricted document by id. Scope narrows, never widens.
        for (String backend : List.of("fts", "pgvector", "qdrant", "hybrid", "rerank", "graph")) {
            List<SearchHit> hits = search.search(BOB, backend, SHARED_TERM, 10, List.of(p), List.of(SECRET_DOC));
            assertThat(hits)
                    .as("backend '%s' honoured a docIds parameter pointing at a restricted document", backend)
                    .noneMatch(h -> h.docId().equals(SECRET_DOC));
        }
    }

    @Test
    void craftedProjectScopeCannotWidenAccess() {
        long p = project();
        // Naming every project (or none at all) does not lift the access filter.
        assertThat(docsOf(search.search(BOB, "hybrid", SHARED_TERM, 10, List.of(), List.of())))
                .doesNotContain(SECRET_DOC);
        assertThat(docsOf(search.search(BOB, "hybrid", SHARED_TERM, 10, List.of(p, 1L), List.of())))
                .doesNotContain(SECRET_DOC);
    }

    @Test
    void rerankerNeverSeesCandidatesTheCallerCannotRead() {
        long p = project();
        // The reranker over-fetches app.rerank.candidates (50) before trimming to topK, so a
        // filter applied after retrieval would still have exposed the chunk to the cross-encoder.
        // Checking the candidate width directly: hybrid at the over-fetch size must already be clean.
        List<SearchHit> candidates = search.search(BOB, "hybrid", SHARED_TERM, 50, List.of(p), List.of());
        assertThat(docsOf(candidates)).doesNotContain(SECRET_DOC);
        assertThat(docsOf(search.search(BOB, "rerank", SHARED_TERM, 10, List.of(p), List.of())))
                .doesNotContain(SECRET_DOC);
    }

    @Test
    void graphExpansionDoesNotWalkIntoARestrictedDocument() {
        long p = project();
        // doc_edge carries no access label, so a link from a readable page to a restricted one is
        // the classic way a graph backend leaks. The chunk load applies the filter.
        jdbc.update("INSERT INTO doc_edge (project_id, src_doc, dst_doc, kind) VALUES (?,?,?,'link') "
                + "ON CONFLICT DO NOTHING", p, PUBLIC_DOC, SECRET_DOC);
        assertThat(docsOf(search.search(BOB, "graph", SHARED_TERM, 10, List.of(p), List.of())))
                .doesNotContain(SECRET_DOC);
        assertThat(docsOf(search.search(ALICE, "graph", SHARED_TERM, 10, List.of(p), List.of())))
                .contains(SECRET_DOC);
    }

    @Test
    void documentListsAndChunkViewsDoNotLeakTitles() {
        long p = project();
        // A title is data: listing a document the caller cannot open still discloses that it exists.
        assertThat(pgVector.listDocuments(BOB, p)).extracting(DocumentSummary::docId)
                .doesNotContain(SECRET_DOC).contains(PUBLIC_DOC);
        assertThat(pgVector.listDocuments(ALICE, p)).extracting(DocumentSummary::docId)
                .contains(SECRET_DOC);

        List<ChunkView> bobsView = pgVector.listChunks(BOB, p, SECRET_DOC);
        assertThat(bobsView).as("chunk view returned the body of a restricted document").isEmpty();
        assertThat(pgVector.listChunks(ALICE, p, SECRET_DOC)).isNotEmpty();
    }

    @Test
    void answersAreGroundedOnlyInReadableChunks() {
        long p = project();
        // AskService retrieves before generating; a chunk that never reaches the prompt cannot be
        // quoted back. Chat uses the same SearchService path with the same context.
        var answer = askService.ask(BOB, SHARED_TERM, List.of(p));
        assertThat(answer.sources()).extracting(s -> s.docId()).doesNotContain(SECRET_DOC);
    }

    @Test
    void feedbackLabelsAreInvisibleOutsideTheirGroup() {
        long p = project();
        feedback.upsert(p, "pay question", SECRET_DOC, 0, "up");
        feedback.upsert(p, "pay question", PUBLIC_DOC, 0, "up");

        // A label carries a document id and someone's query text - the same leak as a title.
        assertThat(feedback.list(BOB, p, null, 100)).extracting(l -> l.docId())
                .doesNotContain(SECRET_DOC).contains(PUBLIC_DOC);
        assertThat(feedback.list(ALICE, p, null, 100)).extracting(l -> l.docId())
                .contains(SECRET_DOC);

        assertThat(pgVector.isVisible(BOB, p, SECRET_DOC, 0)).isFalse();
        assertThat(pgVector.isVisible(ALICE, p, SECRET_DOC, 0)).isTrue();
    }

    @Test
    void aCallerWithNoGroupsReadsNothing() {
        long p = project();
        for (String backend : List.of("fts", "pgvector", "qdrant", "hybrid", "rerank", "graph")) {
            assertThat(search.search(TestContexts.NOBODY, backend, SHARED_TERM, 10, List.of(p), List.of()))
                    .as("backend '%s' returned rows for a caller with no groups - the empty group "
                            + "set must fail closed, not open", backend)
                    .isEmpty();
        }
        assertThat(pgVector.listDocuments(TestContexts.NOBODY, p)).isEmpty();
    }

    @Test
    void ingestRejectsAnUnknownGroupInsteadOfHidingTheDocument() {
        long p = project();
        // A typo'd label would produce a document nobody can ever read, which is far harder to
        // notice than an upload error.
        assertThatThrownBy(() -> ingest.ingestMarkdown(p, "Typo-Doc", "Typo-Doc.md", "# x\n\nbody",
                null, List.of("hrr")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown group");
    }
}
