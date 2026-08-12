package com.example.springbootrag.integration;

import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.config.GuardProperties;
import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.repository.DocumentRegistry;
import com.example.springbootrag.repository.PgVectorRepository;
import com.example.springbootrag.repository.ProjectRepository;
import com.example.springbootrag.repository.QdrantRepository;
import com.example.springbootrag.repository.QuarantineAuditRepository;
import com.example.springbootrag.repository.QuarantineRepository;
import com.example.springbootrag.service.ProjectService;
import com.example.springbootrag.web.IngestController;
import com.example.springbootrag.web.dto.IngestRequest;
import com.example.springbootrag.security.CurrentUser;
import com.example.springbootrag.security.Roles;
import com.example.springbootrag.security.SearchContext;
import com.example.springbootrag.service.RecordIngestService;
import com.example.springbootrag.web.DocumentController;
import com.example.springbootrag.web.QuarantineController;
import com.example.springbootrag.web.dto.RecordRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class QuarantineIntegrationTest {

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
                    java.util.Arrays.fill(v, 0.1f);
                    return v;
                }
                @Override public int dimension() { return 768; }
            };
        }
    }

    @Autowired QuarantineRepository pen;
    @Autowired QuarantineAuditRepository auditRepo;
    @Autowired ProjectRepository projects;
    @Autowired JdbcTemplate jdbc;
    @Autowired DocumentController documents;
    @Autowired PgVectorRepository pgVector;
    @Autowired RecordIngestService records;
    @Autowired QuarantineController quarantine;
    @Autowired QdrantRepository qdrantRepo;
    @Autowired DocumentRegistry registry;
    @Autowired IngestController ingestController;
    @Autowired ProjectService projectService;
    @Autowired GuardProperties guard;

    long projectId;
    long defaultProjectId;
    final SearchContext alice = SearchContext.of("alice", Set.of("public", "finance"));
    final SearchContext outsider = SearchContext.of("bob", Set.of("public"));

    /** Signs in as the given user for a direct controller call. Roles are authorities too. */
    private void authenticateAs(String user, List<String> groups, List<String> roles) {
        List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
        for (String g : groups) {
            authorities.add(new SimpleGrantedAuthority(CurrentUser.GROUP_PREFIX + g));
        }
        for (String r : roles) {
            authorities.add(new SimpleGrantedAuthority(Roles.PREFIX + r));
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "n/a", authorities));
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM quarantine");
        jdbc.update("DELETE FROM quarantine_audit");
        projectId = projects.create("quarantine-test-" + System.nanoTime(), null);
        defaultProjectId = projectService.defaultProjectId();
        // The controller builds its own SearchContext from the authenticated principal - it never
        // takes one as a parameter - so a direct call needs an authentication in place. alice holds
        // the release role; the refusal tests below re-authenticate as someone who does not.
        authenticateAs("alice", List.of("public", "finance"), List.of(Roles.QUARANTINE_RELEASE));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private static MockMultipartFile md(String name, String body) {
        return new MockMultipartFile("file", name, "text/markdown",
                body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * "Not indexed" means every store, not just the one that is easiest to query. Asserting
     * Postgres alone let an earlier version of this suite pass with the Qdrant delete removed,
     * while the secret stayed retrievable through the qdrant, hybrid and rerank backends - the
     * LEARNINGS section 13 bug class this whole unit cites as its precedent.
     */
    private void assertNowhereIndexed(String docId) {
        assertThat(pgVector.listChunks(alice, projectId, docId)).isEmpty();
        assertThat(qdrantHits(docId)).isEmpty();
        assertThat(registry.find(projectId, docId)).isEmpty();
    }

    private List<String> qdrantHits(String docId) {
        float[] probe = new float[768];
        java.util.Arrays.fill(probe, 0.1f);
        try {
            return qdrantRepo.search(alice, probe, 50, List.of(projectId), List.of()).stream()
                    .map(SearchHit::docId)
                    .filter(docId::equals)
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("Qdrant probe failed", e);
        }
    }

    @Test
    void anUploadCarryingACredentialIsHeldAndNeverIndexed() {
        var res = documents.uploadToProject(projectId,
                md("policy.md", "# Expense policy\nThe admin recovery code is hunter2\n"),
                List.of("public"));

        assertThat(res.quarantined()).isTrue();
        assertThat(res.chunksStored()).isZero();
        assertThat(res.findings()).isNotEmpty();
        // The point of the whole unit: nothing to filter out later, because nothing went in.
        assertNowhereIndexed("policy");
        assertThat(pen.find(alice, projectId, "policy")).isPresent();
    }

    @Test
    void theRawTextEndpointIsScannedToo() {
        // POST /ingest reached the index with no scan at all until a review found it. The scan now
        // lives in IngestService, the one method every ingest path funnels through.
        var res = ingestController.ingest(
                new IngestRequest("raw-leak", "the admin recovery code is swordfish"));

        assertThat(res.quarantined()).isTrue();
        assertThat(pgVector.listChunks(alice, defaultProjectId, "raw-leak")).isEmpty();
    }

    @Test
    void quarantineCanBeTurnedOff() {
        // The disabled path exists for a deliberate bulk import of a corpus known to contain
        // credential-shaped text. Untested, it was a branch nobody had ever executed.
        guard.getQuarantine().setEnabled(false);
        try {
            var res = documents.uploadToProject(projectId,
                    md("policy.md", "The admin recovery code is hunter2\n"), List.of("public"));

            assertThat(res.quarantined()).isFalse();
            assertThat(pgVector.listChunks(alice, projectId, "policy")).isNotEmpty();
        } finally {
            guard.getQuarantine().setEnabled(true);
        }
    }

    @Test
    void anOrdinaryUploadIsUnaffected() {
        var res = documents.uploadToProject(projectId,
                md("meals.md", "# Expense policy\nThe meal allowance per day is 40 EUR.\n"),
                List.of("public"));

        assertThat(res.quarantined()).isFalse();
        assertThat(res.chunksStored()).isPositive();
        assertThat(pen.list(alice, projectId)).isEmpty();
    }

    @Test
    void aDocumentThatBecomesUnsafeIsRemovedFromTheIndex() {
        // Version 1 was clean and searchable; version 2 carries a credential. The old copy must
        // not stay retrievable just because it passed the scan when it was uploaded.
        documents.uploadToProject(projectId,
                md("policy.md", "# Expense policy\nThe meal allowance per day is 40 EUR.\n"),
                List.of("public"));
        assertThat(pgVector.listChunks(alice, projectId, "policy")).isNotEmpty();

        var res = documents.uploadToProject(projectId,
                md("policy.md", "# Expense policy\nThe admin recovery code is hunter2\n"),
                List.of("public"));

        assertThat(res.quarantined()).isTrue();
        assertNowhereIndexed("policy");
    }

    private static RecordRequest record(String docId, String note) {
        ObjectNode root = new ObjectMapper().createObjectNode();
        root.putObject("values").put("customer", "ACME").put("note", note);
        return new RecordRequest(docId, "invoice", root, null, List.of("public"), null);
    }

    @Test
    void aRecordCarryingACredentialIsHeldAndNeverIndexed() {
        var res = records.ingest(projectId, record("inv-1", "api key = sk-abcdefghijklmnopqrstuvwx"));

        assertThat(res.status()).isEqualTo("quarantined");
        assertThat(res.chunksStored()).isZero();
        assertThat(res.findings()).isNotEmpty();
        assertNowhereIndexed("inv-1");
        assertThat(pen.find(alice, projectId, "inv-1")).isPresent();
    }

    @Test
    void anAlreadyIndexedRecordThatBecomesUnsafeIsRemovedFromTheIndex() {
        records.ingest(projectId, record("inv-2", "nothing sensitive here"));
        assertThat(pgVector.listChunks(alice, projectId, "inv-2")).isNotEmpty();

        var res = records.ingest(projectId, record("inv-2", "password is hunter2"));

        assertThat(res.status()).isEqualTo("quarantined");
        assertNowhereIndexed("inv-2");
    }

    @Test
    void anUnchangedRecordIsStillQuarantinedRatherThanSkipped() {
        // The hash comparison says "unchanged"; the scanner still says "no". Order matters here -
        // a skipped short-circuit would leave an unsafe document indexed forever.
        RecordRequest req = record("inv-3", "password is hunter2");

        assertThat(records.ingest(projectId, req).status()).isEqualTo("quarantined");
        assertThat(records.ingest(projectId, req).status()).isEqualTo("quarantined");
    }

    @Test
    void releaseIndexesTheHeldDocumentAndEmptiesThePen() {
        documents.uploadToProject(projectId,
                md("policy.md", "# Expense policy\nThe admin recovery code is hunter2\n"),
                List.of("public"));
        assertThat(pgVector.listChunks(alice, projectId, "policy")).isEmpty();

        quarantine.release(projectId, "policy");

        assertThat(pgVector.listChunks(alice, projectId, "policy")).isNotEmpty();
        assertThat(pen.find(alice, projectId, "policy")).isEmpty();
    }

    @Test
    void releaseDoesNotRescanAndSoDoesNotImmediatelyRequarantine() {
        // Re-running the scan on release would refuse the exact document a human just accepted.
        documents.uploadToProject(projectId,
                md("policy.md", "The admin recovery code is hunter2\n"), List.of("public"));

        quarantine.release(projectId, "policy");

        assertThat(pen.list(alice, projectId)).isEmpty();
        assertThat(pgVector.listChunks(alice, projectId, "policy")).isNotEmpty();
    }

    @Test
    void aReleasedRecordIsIndexedToo() {
        records.ingest(projectId, record("inv-9", "password is hunter2"));

        quarantine.release(projectId, "inv-9");

        assertThat(pgVector.listChunks(alice, projectId, "inv-9")).isNotEmpty();
        assertThat(pen.find(alice, projectId, "inv-9")).isEmpty();
    }

    @Test
    void anAuditRowRoundTripsAndItsOutcomeCanBeStamped() {
        long id = auditRepo.record(projectId, "policy", QuarantineAuditRepository.ACTION_RELEASE,
                QuarantineAuditRepository.OUTCOME_ATTEMPTED, "alice",
                "[{\"rule\":\"recovery-code\",\"label\":\"recovery code\",\"excerpt\":\"hun***\"}]",
                List.of("public"));
        long other = auditRepo.record(projectId, "meals", QuarantineAuditRepository.ACTION_HELD,
                QuarantineAuditRepository.OUTCOME_OK, null, "[]", List.of("public"));

        auditRepo.outcome(id, QuarantineAuditRepository.OUTCOME_OK);

        var policy = auditRepo.history(projectId, "policy");
        assertThat(policy).hasSize(1);
        assertThat(policy.get(0).outcome()).isEqualTo("ok");
        assertThat(policy.get(0).principal()).isEqualTo("alice");
        assertThat(policy.get(0).allowedGroups()).containsExactly("public");
        assertThat(policy.get(0).at()).isNotNull();
        // outcome() must touch only the row it names.
        assertThat(auditRepo.history(projectId, "meals").get(0).id()).isEqualTo(other);
        assertThat(auditRepo.history(projectId, "meals").get(0).outcome()).isEqualTo("ok");
    }

    @Test
    void aHeldDocumentMayHaveNoPrincipal() {
        // WikiImporter holds pages from inside a streaming import; a null principal is honest -
        // "the system held this, nobody claimed it" - and must not be an error.
        long id = auditRepo.record(projectId, "wiki-page", QuarantineAuditRepository.ACTION_HELD,
                QuarantineAuditRepository.OUTCOME_OK, null, "[]", List.of("public"));

        assertThat(id).isPositive();
        assertThat(auditRepo.history(projectId, "wiki-page").get(0).principal()).isNull();
    }

    @Test
    void aCallerWithoutTheRoleCannotRelease() {
        documents.uploadToProject(projectId,
                md("policy.md", "The admin recovery code is hunter2\n"), List.of("public"));
        authenticateAs("haiks", List.of("public", "finance"), List.of());

        assertThatThrownBy(() -> quarantine.release(projectId, "policy"))
                .isInstanceOf(AccessDeniedException.class);

        // The control held: still in the pen, still nowhere in the index.
        assertThat(pen.find(alice, projectId, "policy")).isPresent();
        assertNowhereIndexed("policy");
        // Refused before anything was decided: the hold is the only thing in the history.
        assertThat(auditRepo.history(projectId, "policy"))
                .extracting(QuarantineAuditRepository.Entry::action)
                .containsExactly("held");
    }

    @Test
    void aCallerWithoutTheRoleCannotDiscard() {
        // Discard is the irreversible one - the pen holds the only copy of the document once it
        // was un-indexed, so an unprivileged discard destroys the document and its evidence.
        documents.uploadToProject(projectId,
                md("policy.md", "The admin recovery code is hunter2\n"), List.of("public"));
        authenticateAs("haiks", List.of("public", "finance"), List.of());

        assertThatThrownBy(() -> quarantine.discard(projectId, "policy"))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(pen.find(alice, projectId, "policy")).isPresent();
    }

    @Test
    void theRoleIsNotASubstituteForBeingAbleToSeeTheDocument() {
        // Two independent checks. The role says you may act; the group scoping says on what.
        documents.uploadToProject(projectId,
                md("policy.md", "The admin recovery code is hunter2\n"), List.of("finance"));
        authenticateAs("carol", List.of("public"), List.of(Roles.QUARANTINE_RELEASE));

        assertThatThrownBy(() -> quarantine.release(projectId, "policy"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nothing held under");
    }

    @Test
    void aReleaseIsRecordedAgainstThePrincipalWhoMadeIt() {
        documents.uploadToProject(projectId,
                md("policy.md", "The admin recovery code is hunter2\n"), List.of("public"));

        quarantine.release(projectId, "policy");

        var history = auditRepo.history(projectId, "policy");
        assertThat(history).extracting(QuarantineAuditRepository.Entry::action)
                .containsExactly("held", "release");
        assertThat(history.get(1).outcome()).isEqualTo("ok");
        assertThat(history.get(1).principal()).isEqualTo("alice");
        assertThat(history.get(1).allowedGroups()).containsExactly("public");
        // The pen row is gone; the decision is not.
        assertThat(pen.find(alice, projectId, "policy")).isEmpty();
    }

    @Test
    void aDiscardIsRecordedToo() {
        documents.uploadToProject(projectId,
                md("policy.md", "The admin recovery code is hunter2\n"), List.of("public"));

        quarantine.discard(projectId, "policy");

        assertThat(auditRepo.history(projectId, "policy"))
                .extracting(QuarantineAuditRepository.Entry::action)
                .containsExactly("held", "discard");
        assertNowhereIndexed("policy");
        assertThat(pen.find(alice, projectId, "policy")).isEmpty();
    }

    @Test
    void theAuditTrailNeverStoresTheSecretItself() {
        // The pen holds the raw document on purpose and is group-scoped for it. This table is
        // append-only and never pruned, so a raw copy here would outlive every other one.
        documents.uploadToProject(projectId,
                md("policy.md", "The admin recovery code is hunter2\n"), List.of("public"));
        quarantine.release(projectId, "policy");

        // SELECT * on purpose: a column added later is covered by this test without anyone
        // remembering to add it here, which is the only way this assertion stays true.
        List<String> everyValue = jdbc.query("SELECT * FROM quarantine_audit",
                (rs, n) -> {
                    StringBuilder all = new StringBuilder();
                    for (int c = 1; c <= rs.getMetaData().getColumnCount(); c++) {
                        all.append(rs.getString(c)).append(' ');
                    }
                    return all.toString();
                });

        assertThat(everyValue).isNotEmpty();
        assertThat(everyValue).noneMatch(row -> row.contains("hunter2"));
    }

    @Test
    void aReleaseThatFailsBeforeIngestingIsStampedFailed() {
        // Narrower than it first looks, and the name says so. Corrupting raw_text makes the JSON
        // parse throw BEFORE any ingest starts, so this proves the stamping, NOT the partial-state
        // hole left open on 2026-08-11 (a release that dies part way, leaving a document both held
        // and partially indexed). Reaching that state needs a failure injected inside the ingest
        // itself; the `attempted` row exists for it, but nothing here reproduces it.
        records.ingest(projectId, record("inv-7", "password is hunter2"));
        jdbc.update("UPDATE quarantine SET raw_text = '{not json' "
                + "WHERE project_id = ? AND doc_id = ?", projectId, "inv-7");

        assertThatThrownBy(() -> quarantine.release(projectId, "inv-7"))
                .isInstanceOf(IllegalStateException.class);

        var history = auditRepo.history(projectId, "inv-7");
        assertThat(history).extracting(QuarantineAuditRepository.Entry::action)
                .containsExactly("held", "release");
        assertThat(history.get(1).outcome()).isEqualTo("failed");
        // Nothing was released: still held, still not indexed.
        assertThat(pen.find(alice, projectId, "inv-7")).isPresent();
        assertNowhereIndexed("inv-7");
    }

    @Test
    void deletingAProjectRecordsWhatTheCascadeDestroys() {
        // Found by review, and confirmed live: `quarantine.project_id REFERENCES projects(id) ON
        // DELETE CASCADE` means deleting a project destroys every held document - the ONLY copy of
        // each, since they were un-indexed to get there - without going anywhere near the role
        // gate. Without this audit row the surviving 'held' rows would read as "still contained"
        // for a document that no longer exists anywhere.
        documents.uploadToProject(projectId,
                md("policy.md", "The admin recovery code is hunter2\n"), List.of("public"));
        assertThat(pen.find(alice, projectId, "policy")).isPresent();

        projectService.delete(projectId);

        assertThat(auditRepo.history(projectId, "policy"))
                .extracting(QuarantineAuditRepository.Entry::action)
                .containsExactly("held", "discard");
        var cascade = auditRepo.history(projectId, "policy").get(1);
        assertThat(cascade.outcome()).isEqualTo("ok");
        assertThat(cascade.principal()).isEqualTo("alice");
        // The pen row went with the project; the history did not - quarantine_audit has no FK.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM quarantine WHERE project_id = ?", Integer.class, projectId))
                .isZero();
    }

    @Test
    void listingNeverReturnsTheRawText() {
        documents.uploadToProject(projectId,
                md("policy.md", "The admin recovery code is hunter2\n"), List.of("public"));

        var view = quarantine.list(projectId);

        assertThat(view).hasSize(1);
        assertThat(view.toString()).doesNotContain("hunter2");
    }

    @Test
    void discardRemovesItWithoutIndexing() {
        documents.uploadToProject(projectId,
                md("policy.md", "The admin recovery code is hunter2\n"), List.of("public"));

        quarantine.discard(projectId, "policy");

        assertThat(pen.list(alice, projectId)).isEmpty();
        assertNowhereIndexed("policy");
    }

    @Test
    void releasingSomethingYouCannotReadIsNotPossible() {
        // The pen holds raw text. Release must go through the same group filter as every read.
        pen.hold(projectId, new QuarantineRepository.Held("hr-doc", "upload", "hr.md", null,
                "password is hunter2", "[]", List.of("hr"), null));

        assertThatThrownBy(() -> quarantine.release(projectId, "hr-doc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> quarantine.discard(projectId, "hr-doc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(pen.find(alice, projectId, "hr-doc")).isEmpty();
    }

    @Test
    void aHeldDocumentComesBackWithItsFindings() {
        pen.hold(projectId, new QuarantineRepository.Held("policy", "upload", "policy.md", null,
                "the admin recovery code is hunter2",
                "[{\"rule\":\"labelled-credential\",\"label\":\"recovery code\",\"excerpt\":\"recovery code = ***\"}]",
                List.of("finance"), null));

        List<QuarantineRepository.Held> held = pen.list(alice, projectId);

        assertThat(held).hasSize(1);
        assertThat(held.get(0).docId()).isEqualTo("policy");
        assertThat(held.get(0).rawText()).contains("hunter2");
        assertThat(held.get(0).allowedGroups()).containsExactly("finance");
    }

    @Test
    void thePenIsScopedToTheCallersGroups() {
        // The pen holds the raw text of a held document. Listing it for someone outside its groups
        // would leak exactly the content quarantine exists to keep out of reach.
        pen.hold(projectId, new QuarantineRepository.Held("policy", "upload", "policy.md", null,
                "secret is hunter2", "[]", List.of("finance"), null));

        assertThat(pen.list(outsider, projectId)).isEmpty();
        assertThat(pen.find(outsider, projectId, "policy")).isEmpty();
        assertThat(pen.find(alice, projectId, "policy")).isPresent();
    }

    @Test
    void holdingTheSameDocIdTwiceReplacesTheRow() {
        // The upstream extraction pipeline retries. A retry must not accumulate rows.
        pen.hold(projectId, new QuarantineRepository.Held("policy", "record", null, "invoice",
                "first", "[]", List.of("public"), null));
        pen.hold(projectId, new QuarantineRepository.Held("policy", "record", null, "invoice",
                "second", "[]", List.of("public"), null));

        assertThat(pen.list(alice, projectId)).hasSize(1);
        assertThat(pen.find(alice, projectId, "policy").orElseThrow().rawText()).isEqualTo("second");
    }

    @Test
    void droppingRemovesIt() {
        pen.hold(projectId, new QuarantineRepository.Held("policy", "upload", "p.md", null,
                "secret is hunter2", "[]", List.of("public"), null));

        assertThat(pen.drop(projectId, "policy")).isEqualTo(1);
        assertThat(pen.list(alice, projectId)).isEmpty();
        assertThat(pen.drop(projectId, "policy")).isZero();
    }
}
