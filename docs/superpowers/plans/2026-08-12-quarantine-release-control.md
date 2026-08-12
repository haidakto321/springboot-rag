# Quarantine release control Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Require a privileged role to release or discard a quarantined document, and leave an audit row that outlives the pen row it replaces.

**Architecture:** Roles ride the existing Spring Security authority rail (`ROLE_<name>` beside the current `GROUP_<name>`), checked by `@PreAuthorize` on the two mutating quarantine endpoints. A new append-only `quarantine_audit` table records held/release/discard with the principal and the masked findings; release and discard write their row *before* acting and stamp the outcome after, so a release that dies mid-ingest leaves a visible `attempted` row instead of nothing.

**Tech Stack:** Java 21 target / Java 25 runtime, Spring Boot 3.5.6, Spring Security (already a dependency), plain `JdbcTemplate`, JUnit 5 + AssertJ, Testcontainers (pgvector + Qdrant).

Spec: `docs/superpowers/specs/2026-08-12-quarantine-release-control-design.md`

## Global Constraints

- **Never run `git add` or `git commit`.** Global user rule. Each task ends with a "commit is the user's call" step: report what changed and stop. If the user says commit in this session, group related changes into as few commits as reasonable and never add an AI co-author trailer.
- Build with `./mvnw`, never `mvn` (PowerShell: `.\mvnw.cmd`).
- Surefire excludes tagged groups by default; every test in this plan is untagged and runs in the normal suite.
- Suite baseline before this work: **465 tests, 0 failures, 3 skipped**, measured on 2026-08-12. (The
  2026-08-11 session note said 468; that number was wrong. Task 1 measured 468 *with* its 3 new
  tests, which puts the true baseline at 465.) It must not go down.
- Docker must be running for Testcontainers (`docker compose up -d` is *not* needed - the tests spin their own containers). `GraphPropertiesTest` is the test that fails first with "Connection to localhost:5432 refused" when Docker is down.
- Comments in English. No Lombok. No new dependencies (`spring-boot-starter-security` is already present).
- Keep `docs/implementation-notes.md` updated with off-spec decisions and tradeoffs (user rule).
- Never use the "—" character in any file. Use "-".

---

### Task 1: Roles on the user directory

**Files:**
- Create: `src/main/java/com/example/springbootrag/security/Roles.java`
- Modify: `src/main/java/com/example/springbootrag/security/SecurityProperties.java` (inner class `User`)
- Modify: `src/main/java/com/example/springbootrag/security/SecurityConfig.java`
- Modify: `src/main/resources/application.yml` (alice only)
- Test: `src/test/java/com/example/springbootrag/security/SecurityConfigRolesTest.java` (new, offline - no Spring context, no containers)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `Roles.QUARANTINE_RELEASE` = `"quarantine-release"` and `Roles.PREFIX` = `"ROLE_"`, both `public static final String` compile-time constants (Task 2 uses `QUARANTINE_RELEASE` inside an annotation, which only accepts constant expressions).
  - `SecurityProperties.User.getRoles()` / `setRoles(List<String>)`, defaulting to an empty list.
  - `SecurityConfig.userDetailsService(SecurityProperties)` unchanged in signature; it now grants `ROLE_<r>` authorities in addition to `GROUP_<g>`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/springbootrag/security/SecurityConfigRolesTest.java`:

```java
package com.example.springbootrag.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Roles are a separate rail from groups: a group says what you may read, a role says what you may
 * do. Both arrive as authorities so Spring Security owns them end to end.
 */
class SecurityConfigRolesTest {

    private static SecurityProperties.User user(String name, List<String> groups, List<String> roles) {
        SecurityProperties.User u = new SecurityProperties.User();
        u.setUsername(name);
        u.setPassword("pw");
        u.setGroups(groups);
        u.setRoles(roles);
        return u;
    }

    private static UserDetailsService directory(SecurityProperties.User... users) {
        SecurityProperties props = new SecurityProperties();
        props.setUsers(List.of(users));
        return new SecurityConfig().userDetailsService(props);
    }

    private static List<String> authorities(UserDetails details) {
        return details.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void aConfiguredRoleBecomesARoleAuthorityAlongsideTheGroups() {
        UserDetails alice = directory(
                user("alice", List.of("public", "hr"), List.of("quarantine-release")))
                .loadUserByUsername("alice");

        assertThat(authorities(alice)).containsExactlyInAnyOrder(
                "GROUP_public", "GROUP_hr", "ROLE_quarantine-release");
    }

    @Test
    void aUserWithNoRolesGetsNoRoleAuthority() {
        UserDetails haiks = directory(user("haiks", List.of("public"), List.of()))
                .loadUserByUsername("haiks");

        assertThat(authorities(haiks)).containsExactly("GROUP_public");
    }

    @Test
    void rolesAreNotGroupsAndDoNotLeakIntoKnownGroups() {
        // knownGroups() is what ingest validates an access label against. A role appearing there
        // would let a caller label a document 'quarantine-release' and have it accepted.
        SecurityProperties props = new SecurityProperties();
        props.setUsers(List.of(user("alice", List.of("public", "hr"), List.of("quarantine-release"))));

        assertThat(props.knownGroups()).containsExactlyInAnyOrder("public", "hr");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test "-Dtest=SecurityConfigRolesTest"`
Expected: COMPILATION FAILURE - `cannot find symbol: method setRoles(java.util.List<java.lang.String>)`.

- [ ] **Step 3: Add the role constants**

Create `src/main/java/com/example/springbootrag/security/Roles.java`:

```java
package com.example.springbootrag.security;

/**
 * Action permissions, as opposed to the data-visibility labels in {@link SearchContext}.
 *
 * <p>A group answers "what may this caller read". A role answers "what may this caller do". They
 * are kept apart deliberately: joining a group to read that group's documents must never also hand
 * out the right to undo a security control.
 *
 * <p>The names are constants because {@code @PreAuthorize} takes a compile-time constant string -
 * a literal in the annotation and a different literal in application.yml would drift apart with no
 * compile error and no failing test.
 */
public final class Roles {

    /** Spring Security's convention: {@code hasRole('x')} checks for the authority {@code ROLE_x}. */
    public static final String PREFIX = "ROLE_";

    /** May release a held document into the index, or discard it and its evidence. */
    public static final String QUARANTINE_RELEASE = "quarantine-release";

    private Roles() {
    }
}
```

- [ ] **Step 4: Add `roles` to the user directory**

In `src/main/java/com/example/springbootrag/security/SecurityProperties.java`, inside `public static class User`, add the field beside `groups`:

```java
        private List<String> roles = new ArrayList<>();
```

and the accessors beside `getGroups`/`setGroups`:

```java
        /** Action permissions - see {@link Roles}. Deliberately NOT part of {@link #knownGroups()}. */
        public List<String> getRoles() { return roles; }
        public void setRoles(List<String> roles) { this.roles = roles; }
```

Leave `knownGroups()` untouched.

- [ ] **Step 5: Grant the role authorities**

In `src/main/java/com/example/springbootrag/security/SecurityConfig.java`, replace the body of the loop inside `userDetailsService` so both authority kinds are built explicitly:

```java
    @Bean
    UserDetailsService userDetailsService(SecurityProperties props) {
        List<UserDetails> users = new ArrayList<>();
        for (SecurityProperties.User u : props.getUsers()) {
            List<GrantedAuthority> authorities = new ArrayList<>();
            for (String group : u.getGroups()) {
                authorities.add(new SimpleGrantedAuthority(CurrentUser.GROUP_PREFIX + group));
            }
            for (String role : u.getRoles()) {
                authorities.add(new SimpleGrantedAuthority(Roles.PREFIX + role));
            }
            users.add(User.withUsername(u.getUsername())
                    .password("{noop}" + u.getPassword())
                    .authorities(authorities)
                    .build());
        }
        return new InMemoryUserDetailsManager(users);
    }
```

Add the import `org.springframework.security.core.GrantedAuthority`. Update the javadoc above the method: groups become `GROUP_<name>`, roles become `ROLE_<name>`.

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw test "-Dtest=SecurityConfigRolesTest"`
Expected: PASS, 3 tests.

- [ ] **Step 7: Give alice the role in config**

In `src/main/resources/application.yml`, under `app.security.users`, add one line to alice and a comment to haiks:

```yaml
    users:
      - username: alice
        password: alice
        groups: [public, hr]
        roles: [quarantine-release]   # may release/discard a quarantined document
      - username: haiks
        password: 123123
        groups: [public, eng]
        # no roles: releasing a held document is refused with 403
```

- [ ] **Step 8: Run the whole suite**

Run: `./mvnw test`
Expected: 465 + 3 = **468 tests, 0 failures, 3 skipped**. Nothing else consumes roles yet, so no existing test can change.

- [ ] **Step 9: Commit is the user's call**

Report: files changed, test counts. Do not run `git add` or `git commit`.

---

### Task 2: The gate on release and discard

**Files:**
- Modify: `src/main/java/com/example/springbootrag/security/SecurityConfig.java` (add `@EnableMethodSecurity`)
- Modify: `src/main/java/com/example/springbootrag/web/QuarantineController.java:56-74`
- Test: `src/test/java/com/example/springbootrag/integration/QuarantineIntegrationTest.java` (modify `@BeforeEach`, add 3 tests)
- Test: `src/test/java/com/example/springbootrag/eval/InjectionDrillTest.java:123-129` (add the role to its authentication)

**Interfaces:**
- Consumes: `Roles.QUARANTINE_RELEASE` from Task 1.
- Produces: `QuarantineController.release` and `.discard` now throw `org.springframework.security.access.AccessDeniedException` for a caller without the role. Signatures unchanged, so Task 4 can move their bodies without touching this gate.

- [ ] **Step 1: Write the failing tests**

In `QuarantineIntegrationTest`, first add a helper and a role-aware `@BeforeEach`. Replace the existing `setUp` authentication block with:

```java
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
        projectId = projects.create("quarantine-test-" + System.nanoTime(), null);
        defaultProjectId = projectService.defaultProjectId();
        // The controller builds its own SearchContext from the authenticated principal - it never
        // takes one as a parameter - so a direct call needs an authentication in place. alice holds
        // the release role; the refusal tests below re-authenticate as someone who does not.
        authenticateAs("alice", List.of("public", "finance"), List.of(Roles.QUARANTINE_RELEASE));
    }
```

Add imports: `com.example.springbootrag.security.Roles`, and `org.springframework.security.access.AccessDeniedException`.

Then add three tests:

```java
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
```

Add the import `static org.assertj.core.api.Assertions.assertThatThrownBy` if the file does not already have it.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test "-Dtest=QuarantineIntegrationTest"`
Expected: `aCallerWithoutTheRoleCannotRelease` and `aCallerWithoutTheRoleCannotDiscard` FAIL - no exception is thrown, because no gate exists yet. `theRoleIsNotASubstituteForBeingAbleToSeeTheDocument` passes already (group scoping is old behaviour) and is there to stop Task 2 from accidentally replacing one check with the other.

- [ ] **Step 3: Turn on method security**

In `src/main/java/com/example/springbootrag/security/SecurityConfig.java`, add the annotation to the class and the import:

```java
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {
```

Add to the class javadoc:

```java
 * <p>{@code @EnableMethodSecurity} is on because quarantine release is an ACTION permission, and a
 * path matcher in the filter chain would attach that rule to a URL shape rather than to the method
 * it protects - a later rename would disarm it with no compile error and no failing test.
```

- [ ] **Step 4: Gate the two endpoints**

In `src/main/java/com/example/springbootrag/web/QuarantineController.java`, annotate both mutating methods (leave `list` alone):

```java
    /** Indexes the held document under the labels its original ingest carried, then empties the pen. */
    @PostMapping("/projects/{projectId}/quarantine/{docId}/release")
    @PreAuthorize("hasRole('" + Roles.QUARANTINE_RELEASE + "')")
    public void release(@PathVariable long projectId, @PathVariable String docId) {
```

```java
    @DeleteMapping("/projects/{projectId}/quarantine/{docId}")
    @PreAuthorize("hasRole('" + Roles.QUARANTINE_RELEASE + "')")
    public void discard(@PathVariable long projectId, @PathVariable String docId) {
```

Add imports `com.example.springbootrag.security.Roles` and `org.springframework.security.access.prepost.PreAuthorize`. Extend the class javadoc:

```java
 * <p>Reading what is held stays open to anyone whose groups overlap the document - the findings are
 * masked, and an uploader seeing that their own upload was held is the only feedback they get.
 * Releasing and discarding need {@link Roles#QUARANTINE_RELEASE}, because both undo the one
 * blocking control this system has. The group-scoped lookup still runs first: a releaser may act on
 * what they can already see, never on more.
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./mvnw test "-Dtest=QuarantineIntegrationTest"`
Expected: PASS. If the existing release tests now fail with `AccessDeniedException`, the `@BeforeEach` from Step 1 was not applied - alice must hold the role.

**Known gap, stated rather than hidden:** these tests call the controller as a bean, so they assert
`AccessDeniedException`, not HTTP **403**. The status code is Spring Security's default handling via
`ExceptionTranslationFilter` and nothing in this suite exercises it, because no test in this project
drives quarantine over MockMvc. Record it in the Task 5 notes rather than adding a MockMvc harness
for one assertion.

- [ ] **Step 6: Give the drill's alice the role**

In `src/test/java/com/example/springbootrag/eval/InjectionDrillTest.java`, the `@BeforeEach` around line 123 sets an authentication for `alice`. Add the role authority to it:

```java
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a",
                        List.of(new SimpleGrantedAuthority(CurrentUser.GROUP_PREFIX + "public"),
                                new SimpleGrantedAuthority(Roles.PREFIX + Roles.QUARANTINE_RELEASE))));
```

Add the `com.example.springbootrag.security.Roles` import. Update the comment above the release assertion (around line 173) to say the drill now asserts a **privileged** human released the page, which is what the prose always claimed.

- [ ] **Step 7: Run the drill and the whole suite**

Run: `./mvnw test "-Dgroups=eval-injection" "-DexcludedGroups="`
Expected: PASS - it needs Docker but no Ollama.

Run: `./mvnw test`
Expected: **471 tests, 0 failures, 3 skipped**.

- [ ] **Step 8: Commit is the user's call**

Report and stop.

---

### Task 3: The audit table and its repository

**Files:**
- Modify: `src/main/resources/schema.sql` (append after the `quarantine` table, around line 258)
- Create: `src/main/java/com/example/springbootrag/repository/QuarantineAuditRepository.java`
- Test: `src/test/java/com/example/springbootrag/integration/QuarantineIntegrationTest.java` (2 tests + `DELETE FROM quarantine_audit` in `@BeforeEach`)

**Interfaces:**
- Consumes: nothing from Tasks 1-2.
- Produces, all used by Task 4:
  - `QuarantineAuditRepository.record(long projectId, String docId, String action, String outcome, String principal, String findingsJson, List<String> allowedGroups) -> long` (the generated id)
  - `QuarantineAuditRepository.outcome(long id, String outcome) -> void`
  - `QuarantineAuditRepository.history(long projectId, String docId) -> List<Entry>`, oldest first
  - `record Entry(long id, long projectId, String docId, String action, String outcome, String principal, String findingsJson, List<String> allowedGroups, Instant at)`
  - constants `ACTION_HELD`/`ACTION_RELEASE`/`ACTION_DISCARD` and `OUTCOME_ATTEMPTED`/`OUTCOME_OK`/`OUTCOME_FAILED`

- [ ] **Step 1: Write the failing test**

Add to `QuarantineIntegrationTest`. Autowire the repository beside the others:

```java
    @Autowired QuarantineAuditRepository auditRepo;
```

and add `jdbc.update("DELETE FROM quarantine_audit");` as the second line of `@BeforeEach`, right after the `DELETE FROM quarantine`.

```java
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
```

Add the import `com.example.springbootrag.repository.QuarantineAuditRepository`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test "-Dtest=QuarantineIntegrationTest"`
Expected: COMPILATION FAILURE - `cannot find symbol: class QuarantineAuditRepository`.

- [ ] **Step 3: Add the table**

Append to `src/main/resources/schema.sql`, after the `quarantine` table:

```sql
-- ---- Quarantine audit trail (2026-08-12) ----
-- The pen row is DELETED on release or discard, so without this the decision - who let a
-- credential-bearing document into the index, and when - disappears with the evidence.
-- Three deliberate omissions:
--   * NO raw_text column. The pen stores the held document verbatim, credential included, which is
--     why its reads are group-scoped. This table is append-only and never pruned, so copying the
--     raw text here would make the audit trail the longest-lived copy of every secret ever caught.
--     The masked findings name the rule and are enough to review the decision.
--   * NO foreign key to projects. The pen cascades on project delete; the audit must outlive it.
--     Deleting the parent is exactly when the history most needs to survive.
--   * NO unique constraint. Repeated holds of the same doc_id are history, not a conflict - the
--     ingest pipeline retries, and the pen upserts while this table accumulates.
CREATE TABLE IF NOT EXISTS quarantine_audit (
    id             BIGSERIAL PRIMARY KEY,
    project_id     BIGINT NOT NULL,
    doc_id         VARCHAR(255) NOT NULL,
    action         VARCHAR(16) NOT NULL,          -- 'held' | 'release' | 'discard'
    outcome        VARCHAR(16) NOT NULL,          -- 'attempted' | 'ok' | 'failed'
    principal      VARCHAR(255),                  -- null when no authenticated caller (import, tool)
    findings       JSONB NOT NULL,                -- [{rule, label, excerpt}], excerpts masked
    allowed_groups TEXT[] NOT NULL,
    at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS quarantine_audit_doc_idx
    ON quarantine_audit (project_id, doc_id, at);
```

- [ ] **Step 4: Write the repository**

Create `src/main/java/com/example/springbootrag/repository/QuarantineAuditRepository.java`:

```java
package com.example.springbootrag.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Append-only history of every quarantine decision.
 *
 * <p>This table exists because {@code release} and {@code discard} both end in
 * {@code pen.drop(...)}, and the pen row is otherwise the only record that a document was ever
 * held. It carries the masked findings and NEVER the raw text - see the comment in schema.sql.
 *
 * <p>Reads are not group-scoped, because there is no read endpoint: psql is the reader for now
 * (ROADMAP). Adding one means deciding whether a doc id plus a principal is itself sensitive.
 */
@Repository
public class QuarantineAuditRepository {

    public static final String ACTION_HELD = "held";
    public static final String ACTION_RELEASE = "release";
    public static final String ACTION_DISCARD = "discard";

    /** A decision that started and has not been stamped: the row nobody finished. */
    public static final String OUTCOME_ATTEMPTED = "attempted";
    public static final String OUTCOME_OK = "ok";
    /** The system reached a decision and it failed, as opposed to nobody finishing it. */
    public static final String OUTCOME_FAILED = "failed";

    public record Entry(long id, long projectId, String docId, String action, String outcome,
                        String principal, String findingsJson, List<String> allowedGroups,
                        Instant at) {}

    private static final RowMapper<Entry> MAPPER = (rs, n) -> new Entry(
            rs.getLong("id"),
            rs.getLong("project_id"),
            rs.getString("doc_id"),
            rs.getString("action"),
            rs.getString("outcome"),
            rs.getString("principal"),
            rs.getString("findings"),
            toList(rs.getArray("allowed_groups")),
            rs.getTimestamp("at").toInstant());

    private final JdbcTemplate jdbc;

    public QuarantineAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return the generated id, so the caller can stamp the outcome once it knows one */
    public long record(long projectId, String docId, String action, String outcome,
                       String principal, String findingsJson, List<String> allowedGroups) {
        Long id = jdbc.queryForObject("""
            INSERT INTO quarantine_audit (project_id, doc_id, action, outcome, principal,
                                          findings, allowed_groups)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::text[])
            RETURNING id
            """, Long.class, projectId, docId, action, outcome, principal,
                findingsJson == null ? "[]" : findingsJson,
                PgVectorRepository.toArrayLiteral(allowedGroups));
        if (id == null) {
            throw new IllegalStateException("audit insert returned no id for: " + docId);
        }
        return id;
    }

    public void outcome(long id, String outcome) {
        jdbc.update("UPDATE quarantine_audit SET outcome = ? WHERE id = ?", outcome, id);
    }

    /** Oldest first: the history of one document reads top to bottom. */
    public List<Entry> history(long projectId, String docId) {
        return jdbc.query("""
            SELECT id, project_id, doc_id, action, outcome, principal, findings::text AS findings,
                   allowed_groups, at
            FROM quarantine_audit
            WHERE project_id = ? AND doc_id = ?
            ORDER BY at, id
            """, MAPPER, projectId, docId);
    }

    private static List<String> toList(java.sql.Array array) {
        try {
            return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("could not read allowed_groups", e);
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./mvnw test "-Dtest=QuarantineIntegrationTest"`
Expected: PASS. If `relation "quarantine_audit" does not exist`, the container reused an old schema - the schema runs on boot via `spring.sql.init.mode=always`, so a stale container is the cause; let Testcontainers create a fresh one.

- [ ] **Step 6: Run the whole suite**

Run: `./mvnw test`
Expected: **473 tests, 0 failures, 3 skipped**.

- [ ] **Step 7: Commit is the user's call**

Report and stop.

---

### Task 4: Write the audit on every decision

**Files:**
- Create: `src/main/java/com/example/springbootrag/service/QuarantineReleaseService.java`
- Modify: `src/main/java/com/example/springbootrag/web/QuarantineController.java` (release/discard bodies move out; `toRequest` and the `ObjectMapper` move with them)
- Modify: `src/main/java/com/example/springbootrag/service/QuarantineService.java` (audit the hold, best-effort principal)
- Test: `src/test/java/com/example/springbootrag/integration/QuarantineIntegrationTest.java` (4 tests)

**Interfaces:**
- Consumes: `QuarantineAuditRepository` (Task 3), `QuarantineRepository.Held` (existing: `docId, origin, sourceFile, docType, rawText, findingsJson, allowedGroups, createdAt`), `IngestService.ingestMarkdown(long, String, String, String, Instant, List<String>, boolean)`, `RecordIngestService.ingestReleased(long, RecordRequest)`, `CurrentUser.context()`.
- Produces: `QuarantineReleaseService.release(long projectId, QuarantineRepository.Held held)` and `.discard(long projectId, QuarantineRepository.Held held)`, both `void`. The controller resolves the `Held` through the caller's groups and hands it over; the service never does its own lookup, so the visibility check cannot be bypassed by calling the service directly.

**Why a new class rather than a method on `QuarantineService`:** release needs `RecordIngestService`, and `RecordIngestService:49` already injects `QuarantineService`. Putting release there is a constructor-injection cycle that Spring refuses to start.

- [ ] **Step 1: Write the failing tests**

Add to `QuarantineIntegrationTest`:

```java
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
    void aReleaseThatDiesMidIngestLeavesTheDecisionVisible() {
        // The partial-state hole left open on 2026-08-11: a release that fails part way leaves a
        // document both held and partially indexed. This does not fix it - it makes it visible.
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
```

Then extend the Task 2 refusal test so the audit proves the refusal as well as the pen does. Add to
the end of `aCallerWithoutTheRoleCannotRelease`:

```java
        // Refused before anything was decided: the hold is the only thing in the history.
        assertThat(auditRepo.history(projectId, "policy"))
                .extracting(QuarantineAuditRepository.Entry::action)
                .containsExactly("held");
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test "-Dtest=QuarantineIntegrationTest"`
Expected: the four new tests FAIL - `history` is empty, because nothing writes audit rows yet. The
added assertion in `aCallerWithoutTheRoleCannotRelease` fails for the same reason.

- [ ] **Step 3: Write the release service**

Create `src/main/java/com/example/springbootrag/service/QuarantineReleaseService.java`:

```java
package com.example.springbootrag.service;

import com.example.springbootrag.repository.QuarantineAuditRepository;
import com.example.springbootrag.repository.QuarantineRepository;
import com.example.springbootrag.security.CurrentUser;
import com.example.springbootrag.web.dto.RecordRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * The two ways a hold ends, and the audit ordering that makes a half-finished one visible.
 *
 * <p>The decision row is written BEFORE the ingest runs and stamped after. A release that dies
 * between the two - the process is killed, the box loses power - leaves a row reading
 * {@code attempted}, which is a queryable signal that a release started and never finished. A row
 * written afterwards would record nothing at all in exactly the case most worth recording. An
 * exception that the service can see is stamped {@code failed} and rethrown, so the two outcomes
 * do not blur: {@code failed} is a decision the system reached, {@code attempted} is one nobody
 * finished.
 *
 * <p>Separate from {@link QuarantineService} because release needs {@link RecordIngestService},
 * which already injects that class - the merged version is a constructor-injection cycle.
 *
 * <p>Both methods take an already-resolved {@link QuarantineRepository.Held}. The caller looks it
 * up through the requester's groups, so "act on a document you cannot see" stays inexpressible
 * even for a caller holding the role.
 */
@Service
public class QuarantineReleaseService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final QuarantineRepository pen;
    private final QuarantineAuditRepository audit;
    private final IngestService ingest;
    private final RecordIngestService records;
    private final CurrentUser currentUser;

    public QuarantineReleaseService(QuarantineRepository pen, QuarantineAuditRepository audit,
                                    IngestService ingest, RecordIngestService records,
                                    CurrentUser currentUser) {
        this.pen = pen;
        this.audit = audit;
        this.ingest = ingest;
        this.records = records;
        this.currentUser = currentUser;
    }

    /** Indexes the held document under the labels its original ingest carried, then empties the pen. */
    public void release(long projectId, QuarantineRepository.Held held) {
        long id = begin(projectId, held, QuarantineAuditRepository.ACTION_RELEASE);
        try {
            if ("record".equals(held.origin())) {
                records.ingestReleased(projectId, toRequest(held));
            } else {
                // scanForSecrets = false: re-running the rule that held it would refuse the exact
                // document a human just decided to accept.
                ingest.ingestMarkdown(projectId, held.docId(), held.sourceFile(), held.rawText(),
                        null, held.allowedGroups(), false);
            }
            pen.drop(projectId, held.docId());
        } catch (RuntimeException e) {
            audit.outcome(id, QuarantineAuditRepository.OUTCOME_FAILED);
            throw e;
        }
        audit.outcome(id, QuarantineAuditRepository.OUTCOME_OK);
    }

    /** Throws the held document away. Irreversible: the pen holds the only copy. */
    public void discard(long projectId, QuarantineRepository.Held held) {
        long id = begin(projectId, held, QuarantineAuditRepository.ACTION_DISCARD);
        try {
            pen.drop(projectId, held.docId());
        } catch (RuntimeException e) {
            audit.outcome(id, QuarantineAuditRepository.OUTCOME_FAILED);
            throw e;
        }
        audit.outcome(id, QuarantineAuditRepository.OUTCOME_OK);
    }

    private long begin(long projectId, QuarantineRepository.Held held, String action) {
        return audit.record(projectId, held.docId(), action,
                QuarantineAuditRepository.OUTCOME_ATTEMPTED, currentUser.context().principal(),
                held.findingsJson(), held.allowedGroups());
    }

    private RecordRequest toRequest(QuarantineRepository.Held h) {
        try {
            // force=true: the registry row was dropped when the record was held, but a release must
            // re-index even if some other path left a matching hash behind.
            return new RecordRequest(h.docId(), h.docType(), MAPPER.readTree(h.rawText()), null,
                    h.allowedGroups(), Boolean.TRUE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("held record is not valid JSON: " + h.docId(), e);
        }
    }
}
```

- [ ] **Step 4: Thin the controller**

In `src/main/java/com/example/springbootrag/web/QuarantineController.java`: inject `QuarantineReleaseService releaseService` instead of `IngestService ingest` and `RecordIngestService records`; delete the `toRequest` method and the now-unused imports (`JsonProcessingException` stays only if `readFindings` still needs it - it does). Bodies become:

```java
    @PostMapping("/projects/{projectId}/quarantine/{docId}/release")
    @PreAuthorize("hasRole('" + Roles.QUARANTINE_RELEASE + "')")
    public void release(@PathVariable long projectId, @PathVariable String docId) {
        releaseService.release(projectId, require(projectId, docId));
    }

    @DeleteMapping("/projects/{projectId}/quarantine/{docId}")
    @PreAuthorize("hasRole('" + Roles.QUARANTINE_RELEASE + "')")
    public void discard(@PathVariable long projectId, @PathVariable String docId) {
        releaseService.discard(projectId, require(projectId, docId));
    }
```

Move the "Release deliberately does NOT re-scan" paragraph out of the controller javadoc and into `QuarantineReleaseService.release` - it documents behaviour that now lives there.

- [ ] **Step 5: Audit the hold**

In `src/main/java/com/example/springbootrag/service/QuarantineService.java`, inject `QuarantineAuditRepository audit` and `CurrentUser currentUser`, then append to `hold` after the `pen.hold(...)` call:

```java
        // Written AFTER the hold, unlike release. The ordering argument above applies one level up:
        // a row claiming containment before the un-index succeeded would assert something untrue.
        // And while the document sits in the pen, the pen row IS the durable record - the audit's
        // job only starts when that row is deleted.
        audit.record(projectId, docId, QuarantineAuditRepository.ACTION_HELD,
                QuarantineAuditRepository.OUTCOME_OK, principalOrNull(), findingsJson(findings),
                labels(requestedGroups));
```

and add:

```java
    /**
     * Best-effort: WikiImporter holds pages from inside a streaming import and tools call this
     * directly, so there is not always an authenticated caller. A null principal is honest - the
     * system held it and nobody claimed it - and is better than turning a successful quarantine
     * into an authentication error.
     */
    private String principalOrNull() {
        try {
            return currentUser.context().principal();
        } catch (org.springframework.security.access.AccessDeniedException e) {
            return null;
        }
    }
```

`findingsJson(findings)` is computed twice now (once for the pen, once for the audit). Hoist it into a local at the top of `hold` and pass it to both.

- [ ] **Step 6: Run tests to verify they pass**

Run: `./mvnw test "-Dtest=QuarantineIntegrationTest"`
Expected: PASS, all cases.

- [ ] **Step 7: Run the drill and the whole suite**

Run: `./mvnw test "-Dgroups=eval-injection" "-DexcludedGroups="`
Expected: PASS.

Run: `./mvnw test`
Expected: **477 tests, 0 failures, 3 skipped**.

- [ ] **Step 8: Commit is the user's call**

Report and stop.

---

### Task 5: Documentation

**Files:**
- Modify: `docs/ROADMAP.md` (the "Quarantine release: a privilege gate and an audit row" entry under "Planned (not yet built)")
- Modify: `docs/RAG-MASTERY.md` (§5's 2026-08-11 note; the row 5 justification in §9)
- Modify: `docs/LEARNINGS.md` (new numbered section)
- Modify: `docs/implementation-notes.md` (new section - user rule)
- Modify: `README.md` (security config table: the `roles` key)
- Modify: `docs/ARCHITECTURE.md` only if it names the quarantine endpoints; check with `grep -n quarantine docs/ARCHITECTURE.md` and skip if it does not.

- [ ] **Step 1: Move the ROADMAP entry**

Rewrite the "Quarantine release" entry as done, naming what was NOT done: no audit read endpoint (psql is the reader), the partial-ingest bug is now visible but not fixed, roles need a redeploy to change. Add a new "Planned" entry for the read endpoint, including the open question it carries - whether a doc id plus a principal is itself group-sensitive.

- [ ] **Step 2: Update RAG-MASTERY**

In §5's 2026-08-11 note, strike the "release has no privilege gate and leaves no audit row" clause and replace it with what now exists plus the date. In §9, row 5 **stays 2** - state explicitly that one of its three holds is cleared and the other two (the judge is unmeasured and off; streaming can flag but not retract) are untouched, so the score does not move on a third of an objection.

- [ ] **Step 3: Add a LEARNINGS section**

Next free number. Content worth keeping, none of it obvious from the diff:
- A control with an unguarded off switch is not a control. Quarantine blocked the index and every authenticated user could undo it.
- Deleting the row that records a decision deletes the decision. The pen row was doing two jobs - current state and history - and only one of them survives a release.
- Write the decision before the act. It costs one extra statement and turns a silent partial failure into a queryable one.
- An audit table is the longest-lived copy of whatever you put in it. That is the argument for keeping raw text out, and for asserting it in a test rather than a comment.
- The constructor-injection cycle: the obvious home for release (`QuarantineService`) could not have it, because `RecordIngestService` already depends on that class. Found at design time by reading the constructor, not at runtime by a failed boot.

- [ ] **Step 4: Update implementation-notes and README**

`docs/implementation-notes.md`: a section for this unit - decisions taken that were not in the spec, anything that changed during the build, what was left open.

`README.md`: the security section gains `roles` in the `app.security.users` example and one line saying release/discard need `quarantine-release`, everything else needs only authentication.

- [ ] **Step 5: Verify the docs against the code**

Re-read each edited passage against the actual code. Every endpoint path, config key, role name and test count must match what was built. Numbers in docs are claims, and a wrong one is worse than an absent one.

- [ ] **Step 6: Final suite run**

Run: `./mvnw test`
Expected: **477 tests, 0 failures, 3 skipped**.

- [ ] **Step 7: Commit is the user's call**

Report the whole unit: files changed, test count before and after, what was left open.

---

## Verification checklist

- [ ] `haiks` (no role) is refused on release AND discard, with the document still held and still nowhere indexed.
- [ ] A caller holding the role but not the group still gets "nothing held under" - the two checks are independent.
- [ ] `alice` releases; the audit reads `held` then `release/ok` with `principal = 'alice'`.
- [ ] A discard is recorded and the document is nowhere indexed.
- [ ] The string `hunter2` appears in no column of `quarantine_audit`.
- [ ] A release that throws leaves `failed`, the pen row intact, and nothing indexed.
- [ ] `-Dgroups=eval-injection` passes: the drill still releases the poisoned page, now as a privileged user.
- [ ] Full suite 477 tests, 0 failures, 3 skipped.
- [ ] No `git add` / `git commit` was run by the implementer.
