package com.example.springbootrag.integration;

import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.guard.SecretScanner;
import com.example.springbootrag.repository.PgVectorRepository;
import com.example.springbootrag.repository.QuarantineRepository;
import com.example.springbootrag.security.SearchContext;
import com.example.springbootrag.service.IngestService;
import com.example.springbootrag.service.ProjectService;
import com.example.springbootrag.service.QuarantineService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "You may only destroy what you may read", against a real database.
 *
 * <p>The read filter protects every path that RETURNS a chunk. A delete returns nothing - it names
 * a row - so knowing a doc id used to be enough to destroy a document you were never allowed to
 * see, and deleting a project took every held document with it with no check at all.
 *
 * <p>{@code DeleteGuardTest} pins the decision with mocks; this pins the SQL underneath it and the
 * wiring that makes every delete path cross it.
 */
@SpringBootTest(properties = "app.graph.edges=structural")
@Testcontainers
class DeleteAuthorizationIntegrationTest {

    static final int DIM = 768;

    /** embedding is NOT NULL, and these rows are written straight to SQL to control their label. */
    static final String ZERO_VECTOR = "array_fill(0::real, ARRAY[" + DIM + "])::vector";

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
                    float[] v = new float[DIM];
                    v[0] = 1f;
                    return v;
                }
                @Override public int dimension() { return DIM; }
            };
        }
    }

    @Autowired IngestService ingest;
    @Autowired ProjectService projectService;
    @Autowired QuarantineService quarantine;
    @Autowired PgVectorRepository pgVector;
    @Autowired QuarantineRepository pen;
    @Autowired JdbcTemplate jdbc;

    /** Signs the calling thread in. Services are called as beans here, exactly as a controller does. */
    private static void authenticate(String name, String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(name, "n/a",
                        List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList()));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private long project(String name) {
        return projectService.create(name + "-" + System.nanoTime(), null);
    }

    private int chunkCount(long projectId, String docId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM chunks WHERE project_id = ? AND doc_id = ?",
                Integer.class, projectId, docId);
        return n == null ? 0 : n;
    }

    // ---- documents ------------------------------------------------------------------------

    @Test
    void aDocumentOutsideTheCallersGroupsCannotBeDeleted() {
        long p = project("acl-doc");
        ingest.ingestMarkdown(p, "Salary-Bands", "s.md", "# Bands\n\nBand 5 is 120000.",
                null, List.of("hr"));

        authenticate("haiks", "GROUP_public", "GROUP_eng");

        assertThatThrownBy(() -> ingest.delete(p, "Salary-Bands"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(chunkCount(p, "Salary-Bands")).isGreaterThan(0);
    }

    @Test
    void aDocumentInsideTheCallersGroupsIsDeletedNormally() {
        long p = project("acl-doc-ok");
        ingest.ingestMarkdown(p, "Onboarding", "o.md", "# Onboarding\n\nLaptops and badges.",
                null, List.of("public"));

        authenticate("haiks", "GROUP_public", "GROUP_eng");
        ingest.delete(p, "Onboarding");

        assertThat(chunkCount(p, "Onboarding")).isZero();
    }

    @Test
    void oneUnreadableChunkIsEnoughToRefuseTheWholeDocument() {
        // A delete takes the document whole, so partial read access must not authorise it.
        long p = project("acl-mixed");
        ingest.ingestMarkdown(p, "Mixed", "m.md", "# Mixed\n\nOpen part.", null, List.of("public"));
        jdbc.update("INSERT INTO chunks (doc_id, chunk_index, content, project_id, allowed_groups, embedding) " +
                "VALUES (?, 99, 'closed part', ?, ARRAY['hr'], " + ZERO_VECTOR + ")", "Mixed", p);

        authenticate("haiks", "GROUP_public", "GROUP_eng");

        assertThatThrownBy(() -> ingest.delete(p, "Mixed")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anUnlabelledChunkIsUnreadableAndBlocksTheDelete() {
        // allowed_groups is nullable; array overlap against NULL is NULL, not false. Without the
        // COALESCE in the count, a row nobody may read would be deletable by anybody.
        long p = project("acl-null");
        jdbc.update("INSERT INTO chunks (doc_id, chunk_index, content, project_id, allowed_groups, embedding) " +
                "VALUES ('Unlabelled', 0, 'orphan', ?, NULL, " + ZERO_VECTOR + ")", p);

        authenticate("haiks", "GROUP_public", "GROUP_eng");

        assertThatThrownBy(() -> ingest.delete(p, "Unlabelled"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aServerSideDeleteWithNoPrincipalIsStillAllowed() {
        // Re-ingest, quarantine containment and the wiki importer all delete without a principal.
        long p = project("acl-internal");
        ingest.ingestMarkdown(p, "Secret", "s.md", "# Secret\n\nBand 5.", null, List.of("hr"));

        SecurityContextHolder.clearContext();

        assertThatCode(() -> ingest.delete(p, "Secret")).doesNotThrowAnyException();
        assertThat(chunkCount(p, "Secret")).isZero();
    }

    @Test
    void reIngestingSomeoneElsesRestrictedDocumentIdIsRefused() {
        // The same hole from the other side: ingest deletes the previous version first, so without
        // the guard haiks could overwrite the contents of an hr document by re-using its doc id.
        long p = project("acl-hijack");
        ingest.ingestMarkdown(p, "Salary-Bands", "s.md", "# Bands\n\nBand 5 is 120000.",
                null, List.of("hr"));

        authenticate("haiks", "GROUP_public", "GROUP_eng");

        assertThatThrownBy(() -> ingest.ingestMarkdown(p, "Salary-Bands", "s.md",
                "# Bands\n\nBand 5 is 1.", null, List.of("public")))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM chunks WHERE project_id = ? AND doc_id = ? AND allowed_groups @> ARRAY['hr']",
                Integer.class, p, "Salary-Bands")).isGreaterThan(0);
    }

    @Test
    void quarantiningAnUploadUnderSomeoneElsesRestrictedDocIdIsRefusedRatherThanHeld() {
        // Containment un-indexes what it holds, so QuarantineService.hold crosses the same funnel.
        // If the uploader cannot read the doc id they are re-using, the hold is refused - and the
        // secret is neither indexed nor held: it is rejected outright. Written down because
        // "containment failed" reads alarming until you see that nothing was ever stored.
        long p = project("acl-hold");
        ingest.ingestMarkdown(p, "Salary-Bands", "s.md", "# Bands\n\nBand 5 is 120000.",
                null, List.of("hr"));

        authenticate("haiks", "GROUP_public", "GROUP_eng");

        assertThatThrownBy(() -> quarantine.hold(p, "Salary-Bands", "upload", "s.md", null,
                "the recovery code is hunter2", List.of("public"),
                List.of(new SecretScanner.Finding("password", "credential", "recovery code = ***")),
                "haiks"))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM quarantine WHERE project_id = ?",
                Integer.class, p)).isZero();
        assertThat(chunkCount(p, "Salary-Bands")).isGreaterThan(0);
    }

    // ---- projects -------------------------------------------------------------------------

    @Test
    void aProjectHoldingAnUnreadableDocumentCannotBeDeletedEvenWithTheRole() {
        long p = project("acl-project");
        ingest.ingestMarkdown(p, "Salary-Bands", "s.md", "# Bands\n\nBand 5 is 120000.",
                null, List.of("hr"));

        authenticate("haiks", "GROUP_public", "ROLE_" + com.example.springbootrag.security.Roles.PROJECT_DELETE);

        assertThatThrownBy(() -> projectService.delete(p)).isInstanceOf(AccessDeniedException.class);
        assertThat(projectService.exists(p)).isTrue();
    }

    @Test
    void aProjectWhoseContentIsFullyReadableIsDeletedByARoleHolder() {
        long p = project("acl-project-ok");
        ingest.ingestMarkdown(p, "Onboarding", "o.md", "# Onboarding\n\nLaptops.",
                null, List.of("public"));

        authenticate("alice", "GROUP_public", "ROLE_" + com.example.springbootrag.security.Roles.PROJECT_DELETE);
        projectService.delete(p);

        assertThat(projectService.exists(p)).isFalse();
    }

    @Test
    void aProjectDeleteWithoutTheRoleIsRefusedAtTheServiceNotOnlyAtTheController() {
        long p = project("acl-project-role");

        authenticate("haiks", "GROUP_public", "GROUP_eng");

        assertThatThrownBy(() -> projectService.delete(p)).isInstanceOf(AccessDeniedException.class);
        assertThat(projectService.exists(p)).isTrue();
    }

    @Test
    void anUnreadableHeldDocumentBlocksTheProjectDeleteThoughTheIndexIsEmpty() {
        // The pen holds the ONLY copy of what is in it, and it cascades from the project row.
        // Counting the index alone would read this project as empty and hand the pen over.
        long p = project("acl-pen");
        quarantine.hold(p, "Leaked-Keys", "upload", "k.md", null, "api key is hunter2",
                List.of("hr"), List.of(new SecretScanner.Finding("password", "credential", "api key is ...")),
                "alice");

        assertThat(pen.countUnreadableHeld(SearchContext.of("haiks", Set.of("public", "eng")), p))
                .isEqualTo(1);

        authenticate("haiks", "GROUP_public", "GROUP_eng",
                "ROLE_" + com.example.springbootrag.security.Roles.PROJECT_DELETE);

        assertThatThrownBy(() -> projectService.delete(p)).isInstanceOf(AccessDeniedException.class);
        assertThat(projectService.exists(p)).isTrue();
    }

    @Test
    void aRefusedProjectDeleteWritesNoAuditRow() {
        // The 2026-08-12 property, kept: a refused call never begins a decision, so the history
        // records attempts that happened rather than attempts that were rejected at the door.
        long p = project("acl-audit");
        quarantine.hold(p, "Leaked-Keys", "upload", "k.md", null, "api key is hunter2",
                List.of("hr"), List.of(new SecretScanner.Finding("password", "credential", "api key is ...")),
                "alice");

        authenticate("haiks", "GROUP_public", "GROUP_eng",
                "ROLE_" + com.example.springbootrag.security.Roles.PROJECT_DELETE);
        assertThatThrownBy(() -> projectService.delete(p)).isInstanceOf(AccessDeniedException.class);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM quarantine_audit WHERE project_id = ? AND action = 'discard'",
                Integer.class, p)).isZero();
    }
}
