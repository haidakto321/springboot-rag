# Quarantine release: a privilege gate and an audit row - design

Date: 2026-08-12
Status: approved, not yet planned
Builds on: the quarantine built on 2026-08-11 (`docs/superpowers/specs/2026-08-11-injection-hardening-design.md`
§1, commits `c705cfa`/`1526038`/`f561cf8`/`95fbb43`) and the gap that spec left open on purpose

## Purpose

Quarantine is the only *blocking* control this system has. A document carrying a credential never
reaches `chunks`, Qdrant, or the `document` registry - the model is never given the chance to be
clever about it. Everything else in §5 is a control on the answer; this one is a control on the
index.

Two things are wrong with the way that control can be undone.

1. **Anyone may undo it.** `POST /projects/{id}/quarantine/{docId}/release` is scoped to the
   caller's groups and nothing else. Every user in this sandbox is in `public`, so every
   authenticated user can release every held document. The blocking control has an unguarded off
   switch.
2. **Nothing records who did.** `release` and `discard` both end in `pen.drop(projectId, docId)`.
   The pen row is the only record that the document was ever held, what tripped the scanner, and
   under which labels it was going to be indexed. After a release, the decision is unrecoverable -
   there is no answer to "who let this in, and when".

`RAG-MASTERY.md` §5's 2026-08-11 note names this as a gap in the *design*, not the implementation:
§1.4 of that spec only ever asked for group scoping. This design fills it.

**Scorecard:** `RAG-MASTERY.md` row 5 stays **2**. This clears one of the three holds named against
it; the other two (the judge is unmeasured and off, streaming can flag but not retract a
groundedness failure) are untouched here. A row does not move because a third of its objection was
answered.

## Scope

**In:** a role, carried as a Spring Security authority, required by `release` and `discard`; a
`quarantine_audit` table that outlives the pen row and records held/release/discard with the
principal and the masked findings; and tests that prove an unprivileged caller is refused and that
a failed release still leaves a trace.

**Out:** an audit read endpoint (psql is the reader for now - see §5). Any quarantine UI; there is
none today and this does not add one. Per-project or per-document roles. Changing release's
deliberate no-re-scan behaviour. Fixing the partial-ingest bug itself - §2.3 makes it *visible*,
which is a different thing and is stated as such.

**Unchanged:** `SecretScanner`, the hold path's ordering, `GET /projects/{id}/quarantine` and its
group scoping, and the behaviour of every endpoint outside `/quarantine`.

---

## 1. Unit A - the role

### 1.1 Roles ride the same rail groups already ride

`SecurityProperties.User` gains `roles`, a list defaulting to empty. `SecurityConfig` grants each
one as an authority named `ROLE_<role>`, beside the `GROUP_<group>` authorities it already grants.

```yaml
app.security:
  users:
    - username: alice
      password: alice
      groups: [public, hr]
      roles:  [quarantine-release]   # new
    - username: haiks
      password: 123123
      groups: [public, eng]
      # no roles -> 403 on release and discard
```

This is chosen over a config-listed principal allow-list compared inside the controller, and over
treating membership of some group as the privilege. The allow-list would be a second authorisation
mechanism living outside the filter chain, which is precisely what §1 avoided when it made
`CurrentUser` the only place a `SearchContext` is built. The group approach conflates a
data-visibility label with an action permission: joining `hr` to read HR documents would also hand
out release rights.

`SecurityProperties.knownGroups()` is untouched. Roles are not groups and must not leak into the
set that ingest validates labels against.

### 1.2 The check lives on the method

`SecurityConfig` gains `@EnableMethodSecurity`. `QuarantineController.release` and
`QuarantineController.discard` each carry:

```java
@PreAuthorize("hasRole('" + Roles.QUARANTINE_RELEASE + "')")
```

The role name is a constant (`security/Roles.java`), so the yaml value, the annotation, and the
tests cannot drift apart silently.

The alternative - path matchers in the filter chain - was rejected because the authorisation rule
would then be attached to a URL shape rather than to the method it protects, and a later path
rename would disarm it with no compile error and no test failure.

`@EnableMethodSecurity` is a global switch, but nothing else in `src/main/java` carries a method
security annotation today (verified). Its only effect is that these two annotations start working.

### 1.3 The role does not replace visibility

`require(projectId, docId)` stays exactly as it is, and stays first in the method body: the row is
looked up **through the caller's groups**, so releasing something you cannot read remains
inexpressible. A releaser is someone who may act on what they can already see, not someone who can
see more. Both checks apply; neither substitutes for the other.

### 1.4 Failure shape

`@PreAuthorize` throws `AccessDeniedException`, which Spring Security's `ExceptionTranslationFilter`
turns into **403** for an authenticated caller. No new `@ExceptionHandler` is added -
`GlobalExceptionHandler` deliberately stays out of the way, because an authorisation failure that
got mapped by application code could be turned into a 200 by a later refactor of that class.

---

## 2. Unit B - the audit

### 2.1 The table

```sql
CREATE TABLE IF NOT EXISTS quarantine_audit (
    id             BIGSERIAL PRIMARY KEY,
    project_id     BIGINT NOT NULL,
    doc_id         VARCHAR(255) NOT NULL,
    action         VARCHAR(16) NOT NULL,   -- 'held' | 'release' | 'discard'
    outcome        VARCHAR(16) NOT NULL,   -- 'attempted' | 'ok' | 'failed'
    principal      VARCHAR(255),           -- null when no authenticated caller
    findings       JSONB NOT NULL,         -- masked excerpts, copied from the pen row
    allowed_groups TEXT[] NOT NULL,
    at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS quarantine_audit_doc_idx ON quarantine_audit (project_id, doc_id, at DESC);
```

Three properties are deliberate.

**No `raw_text` column.** The pen stores the held document verbatim, credential included; that is
why `QuarantineRepository`'s reads are group-scoped. An audit table is append-only and never
deleted, so copying the raw text there would turn the audit trail into the longest-lived copy of
every secret the scanner ever caught. The findings are enough: they name the rule and carry a
masked excerpt. A test asserts the secret string is absent from the whole table.

**No foreign key to `projects`.** The pen has `ON DELETE CASCADE`; the audit must survive the
project. A record of who released a credential-bearing document is worth more than referential
tidiness, and it is the deletion of the parent row that most needs to leave the history intact.

**No unique constraint.** Repeated holds of the same `docId` (the ingest pipeline retries) are
history, not a conflict. The pen upserts; the audit accumulates.

### 2.2 Where the writes happen

Release and discard move out of the controller into a new `QuarantineReleaseService`, so the
ordering argument lives next to the code it orders rather than in a web layer. The controller keeps
the `@PreAuthorize`, the group-scoped `require()`, and nothing else.

**Why a new class and not `QuarantineService`**, which is the obvious home: release needs
`RecordIngestService`, and `RecordIngestService` already depends on `QuarantineService`
(`RecordIngestService:49`). Putting release there makes a constructor-injection cycle that Spring
refuses to start. The new service depends on the pen, the audit repository, `IngestService`,
`RecordIngestService`, and `CurrentUser`; nothing depends on it but the controller, so there is no
cycle. `QuarantineService` keeps the hold path and gains only its own audit write.

New `QuarantineAuditRepository` with two methods: `long record(...)` returning the generated id, and
`void outcome(long id, String outcome)`.

### 2.3 Release writes the decision *before* it acts

```
require()                              // group-scoped lookup, unchanged
id = audit.record(release, attempted)  // committed on its own
ingest (record path or markdown path)
pen.drop()
audit.outcome(id, ok)
```

There is no `@Transactional` anywhere in `src/main/java` (verified), so every `jdbc.update`
auto-commits and this ordering needs no transaction gymnastics.

The point of writing first is the failure case. The 2026-08-11 spec left a known hole: a release
that dies mid-ingest leaves a document both held and partially indexed. Today that state is silent.
After this change it leaves **a row stuck at `attempted`**, which is a queryable signal that a
release started and never finished. This design does not fix that bug. It makes it visible, and says
so.

`outcome = 'failed'` is written when the ingest throws and the service catches it, marks the row,
and rethrows - so every exception that reaches the caller has already been recorded. Only something
that kills the process between the two writes leaves `attempted`. The values are not
interchangeable: `failed` is a decision the system reached, `attempted` is a decision nobody
finished.

Discard follows the identical shape. It is gated too, and for the sharper reason: once a document
was un-indexed into the pen, the pen holds the **only** copy. Release re-indexes; discard destroys
the document and its evidence, and is the irreversible one.

### 2.4 `held` writes after, and why the asymmetry is not sloppiness

`QuarantineService.hold` writes its audit row **after** the un-index and the pen write have both
succeeded, with `outcome = 'ok'`.

The hold path's existing javadoc argues the ordering: un-index first, then record the hold, because
the reverse fails unsafe - a pen row committed against a failed Qdrant delete would tell an operator
a document is contained while it is still searchable. An audit row written before the hold would
have the same defect one level up: it would assert a containment that had not happened yet.

And while the document sits in the pen, the pen row *is* the durable record. The audit's job is to
survive that row's deletion, which is a job that only starts at release or discard.

`held` rows are kept rather than dropped as redundant, because they are the only record of a
document that was held and then discarded - the release row's absence would otherwise be the only
trace, and an absence is not evidence.

### 2.5 The principal

`CurrentUser.context().principal()` on the release and discard paths - those always run inside an
authenticated request.

On the hold path it is resolved **best-effort**: the principal when a security context exists, null
otherwise. `WikiImporter` holds pages from inside a streaming import and tests call the service
directly; neither should turn a successful quarantine into an authentication error. A null principal
on a `held` row is honest - "the system held this, nobody claimed it" - whereas an exception there
would lose the hold entirely.

---

## 3. How this is proved

Everything below is offline or Testcontainers. **No Ollama, no live corpus, no eval run.** This is
the whole reason this unit was chosen while the box is memory-starved.

### 3.1 The refusals, against a real unprivileged user

`QuarantineIntegrationTest` gains cases driven by `haiks`, who has no role in `application.yml` -
not a mock, the same user the rest of the suite authenticates as:

- `haiks` releases a held document -> `AccessDeniedException`; the pen row is **still there**; the
  chunks are **still absent** from Postgres and Qdrant; no `release/ok` row exists.
- `haiks` discards -> `AccessDeniedException`; the pen row survives.
- `alice` releases -> succeeds, and the audit holds exactly `held/ok` then `release/ok`, with
  `principal = 'alice'` on the second.
- `alice` discards a different document -> `held/ok` then `discard/ok`, and the chunks never appear.
- **A caller with the role but not the group** still gets the existing "nothing held under: x" -
  proof that §1.3's two checks are independent.

Both existing tests autowire `QuarantineController` as a bean, so `@PreAuthorize` applies through
the proxy; their hand-built `UsernamePasswordAuthenticationToken` for `alice` gains the
`ROLE_quarantine-release` authority.

### 3.2 The two assertions that would not be written by a comfortable suite

- **The secret is not in the audit table.** Query `quarantine_audit` in full - every column of every
  row - and assert the held document's credential string appears nowhere. The 2026-08-11 lesson was
  that "never indexed" assertions which only checked Postgres let a Qdrant leak pass all sixteen
  tests; the same class of mistake here is an audit trail that quietly becomes a secret store.
- **A failed release leaves a row behind.** Corrupt a held record's `raw_text` to invalid JSON via
  `jdbc`, then release it: parsing throws, and the test asserts the audit row reads `failed`, the
  exception still reaches the caller, the pen row still exists, and nothing was indexed. This is the
  partial-state bug reproduced deliberately, so the signal it now emits is tested rather than
  assumed.

### 3.3 The drill

`InjectionDrillTest` releases the poisoned page as part of its assertion that `hunter2` comes back
after a human decision. Its `alice` gains the role. The test is not weakened by this - it is
sharpened: the drill now asserts that a **privileged** human released it, which is what it was
always claiming in prose.

### 3.4 Offline unit tests

- `SecurityConfig` grants `ROLE_` authorities for configured roles and none for a user without any.
- `knownGroups()` is unaffected by roles.

`QuarantineAuditRepository`'s own round-trip - insert returns an id, `outcome()` updates only the
row it names - needs a real Postgres, so it belongs in `QuarantineIntegrationTest`, which already
has one running, rather than spinning a second container set for two assertions.

---

## 4. Files

**New**
- `src/main/java/com/example/springbootrag/security/Roles.java`
- `src/main/java/com/example/springbootrag/repository/QuarantineAuditRepository.java`
- `src/main/java/com/example/springbootrag/service/QuarantineReleaseService.java`

**Changed**
- `src/main/resources/schema.sql` - `quarantine_audit` table + index
- `src/main/java/.../security/SecurityProperties.java` - `User.roles`
- `src/main/java/.../security/SecurityConfig.java` - `@EnableMethodSecurity`, grant `ROLE_*`
- `src/main/java/.../web/QuarantineController.java` - `@PreAuthorize` x2; release/discard bodies
  move to `QuarantineReleaseService`
- `src/main/java/.../service/QuarantineService.java` - the `held` audit write and best-effort
  principal resolution
- `src/main/resources/application.yml` - alice's role
- `src/test/java/.../integration/QuarantineIntegrationTest.java`
- `src/test/java/.../eval/InjectionDrillTest.java`
- `docs/ROADMAP.md`, `docs/RAG-MASTERY.md` §5, `docs/LEARNINGS.md`, `docs/implementation-notes.md`,
  `README.md`

---

## 5. Risks and what is knowingly left undone

**No audit reader.** The history is queryable only through psql. A read endpoint would need a DTO, a
controller test, and a decision about whether the audit is group-scoped (the findings are masked,
but a doc id and a principal are not nothing). Deferred to `ROADMAP.md` rather than guessed at.

**A misconfigured role can wedge the pen.** If no user holds `quarantine-release`, held documents
can be neither released nor discarded and the pen fills up. Accepted: the sandbox has two users and
one of them holds it. On a real deployment this is the same operational problem as any admin role,
and the fix is an identity provider, not a fallback.

**Roles are config, so a change needs a redeploy.** Identical to the limitation already recorded
against groups in `RAG-MASTERY.md` row 1, and it is one of the reasons that row is a 2 and not more.

**The partial-ingest bug stays open.** §2.3 gives it a signal; it does not give it a fix. Making a
silent failure loud is worth doing on its own, and pretending otherwise in the ROADMAP entry would
be the dishonest version.

**`RecordRequest.metadata` is still unscanned** (open from 2026-08-11). Unrelated to this change and
left where it is.
