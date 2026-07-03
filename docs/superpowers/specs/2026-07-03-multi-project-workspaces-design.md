# Design: Multi-Project Workspaces

Date: 2026-07-03
Scope: Introduce a project layer between the app and documents, with optional grouping.
Documents (and their chunks) belong to a project; projects may share a group name.
Search / ask / compare operate within an active project, optionally widened to its group.

## Goal

Today all chunks live in one flat namespace (`chunks.doc_id`). This lets a user keep several
independent knowledge bases (e.g. a Frontend project and a Backend project) side by side,
switch between them, and optionally treat related projects as one group for cross-project
search (e.g. a "MyApp" group over its FE + BE projects).

## Decisions (settled with user)

1. **Hierarchy:** Group (optional) -> Project (required) -> Document -> Chunk.
2. **Storage (Approach A):** a `projects` table; "group" is a nullable `group_name` label on the
   project. Groups are emergent (distinct non-null `group_name`s) - no separate groups table.
   Adequate for a local, single-user tool; promoting the label to a real entity later is a small
   migration if ever needed.
3. **Scope model:** an **active project** drives the whole UI (documents, import, search, ask,
   compare). A **"search whole group"** toggle widens search/ask/compare to every project sharing
   the active project's `group_name`. Existing document-scope chips still filter within.
4. **Migration:** create a `Default` project (no group) and backfill all existing chunks to it.
5. **doc uniqueness:** `(project_id, doc_id)` - the same filename can exist in different projects;
   re-upload replaces within its project.

## Data model

New table:
```sql
CREATE TABLE projects (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    group_name VARCHAR(255),                 -- null = ungrouped
    created_at TIMESTAMP DEFAULT now()
);
```
`chunks` gains `project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE`.

Migration (idempotent, in `schema.sql`):
1. `CREATE TABLE IF NOT EXISTS projects ...`.
2. `ALTER TABLE chunks ADD COLUMN IF NOT EXISTS project_id BIGINT`.
3. Seed a `Default` project if none exists; backfill `chunks.project_id` where null to it.
4. Add FK + `NOT NULL` (guarded so re-runs don't fail) and
   `CREATE INDEX IF NOT EXISTS idx_chunks_project ON chunks(project_id)`.
5. Replace any doc-id uniqueness assumption: uniqueness is enforced logically by delete-before-insert
   keyed on `(project_id, doc_id)` (matches current behavior, now project-scoped).

**Qdrant:** add `project_id` to each point's payload. Filtering by project = a `matchKeyword`
(or integer match) on `project_id`; group/multi-project = OR'd conditions over the resolved
project ids (reuse the Unit C filter pattern, keyed on `project_id` instead of `doc_id`, combinable
with the existing `doc_id` filter).

## API

**Projects**
- `POST /projects` `{ name, groupName? }` -> created project.
- `GET /projects` -> `[{ id, name, groupName, docCount, chunkCount }]`.
- `PATCH /projects/{id}` `{ name?, groupName? }` -> rename / (re)assign or clear group
  (`groupName: null` clears).
- `DELETE /projects/{id}` -> deletes the project and cascades its chunks (pg + Qdrant).
- `GET /groups` -> `[String]` distinct non-null group names (for the picker).

**Documents (now project-scoped)**
- `POST /projects/{id}/documents` (multipart md upload)
- `GET /projects/{id}/documents`
- `DELETE /projects/{id}/documents/{docId}`
- `GET /projects/{id}/documents/{docId}/chunks`

**Search / ask / compare / chat** gain `projectId` (required) and `group` (bool, default false):
- `GET /search?...&projectId=&group=`
- `GET /compare?...&projectId=&group=`
- `GET /ask?...&projectId=&group=`
- `POST /chat/stream` body adds `projectId`, `group`.
When `group=true`, the service resolves the active project's `group_name` -> the set of project
ids sharing it, and filters over that set; otherwise it filters to the single `projectId`. The
existing `docIds` filter still applies on top.

**Backward compatibility:** the old flat endpoints (`/documents`, `/search`, `/ask`, `/compare`,
`/ingest`) are repointed at the `Default` project so existing scripts/tests keep working during
the transition; the UI switches to the project-scoped routes.

## Services & repositories

- **`ProjectService` / `ProjectRepository`** (new): CRUD over `projects`; `listWithCounts`
  (join chunk counts); `resolveScope(projectId, group)` -> `List<Long> projectIds`
  (single id, or all ids in the group).
- **`IngestService`**: every ingest takes a `projectId`; `insert`/`upsert`/`delete` carry it;
  delete-before-insert keyed on `(project_id, doc_id)`.
- **Repositories** (`PgFtsRepository`, `PgVectorRepository`, `QdrantRepository`): `search(...)`
  gains a `List<Long> projectIds` filter (empty = all - used only by the compatibility layer),
  combined with the existing `docIds` filter. `listDocuments` / `listChunks` / `deleteByDocId`
  become project-scoped.
- **`SearchService` / `AskService` / `ChatService`**: thread `projectIds` (from
  `resolveScope`) through `search`/`compare`/`ask`/`chatStream`, alongside `docIds`.

Scope precedence: `projectIds` (from project or group) narrows the corpus first; `docIds`
narrows within that. Both applied in-query (never post-filter), same rule as Unit C.

## UI

- **Project switcher** in the sidebar (under the logo): a dropdown of projects grouped by
  `group_name`, showing the active one, plus **＋ New project** and **Manage**. Active project id
  persisted in localStorage; restored on load; falls back to `Default` / first project.
- **Manage projects modal:** create (name + group picker: existing group via `/groups` or type a
  new one), rename, change/clear group, delete (with confirm). Lightweight overlay, not a new nav
  screen.
- **Documents screen, import, chunk view:** all operate on the active project via the
  `/projects/{id}/...` routes; stats and counts reflect the active project.
- **Search & Ask / Compare:** send `projectId`; add a **"Search whole group (<name>)"** checkbox by
  the scope chips, shown only when the active project has a group. Doc-scope chips unchanged.
- **Empty states:** on first load with no projects, auto-create and select `Default`; per-project
  "no documents yet" nudge.

## Testing

- **Migration/integration:** existing chunks land in `Default`; new schema applies idempotently on
  re-run.
- **Repository/integration (Testcontainers):** chunks carry `project_id`; search filters to a
  project; group search spans a group's projects; `docIds` still narrows within a project; delete
  cascades pg + Qdrant.
- **Service unit tests:** `resolveScope` (single vs group); scope precedence (project vs group vs
  docIds); ingest writes the given `project_id`.
- **Controller tests:** project CRUD; project-scoped document routes; `projectId`/`group` params on
  search/ask/compare/chat.

## Implementation phasing (one spec, built in order)

1. **Schema + migration** - `projects` table, `chunks.project_id`, `Default` backfill, Qdrant
   payload `project_id`.
2. **Project/group CRUD** - `ProjectRepository`, `ProjectService`, `ProjectController`, `/groups`.
3. **Project-scoped ingest + retrieval** - thread `projectId`/`projectIds` through ingest, repos,
   `SearchService`/`AskService`/`ChatService`, controllers; repoint legacy endpoints at `Default`.
4. **UI** - project switcher, manage modal, project-scoped document/search/ask/compare wiring,
   group toggle.

## Out of scope (future)
- Groups as first-class entities (own id/metadata/permissions).
- Per-project settings (model, chunking params).
- Moving a document between projects (delete + re-upload for now).
- Multi-user / auth.
