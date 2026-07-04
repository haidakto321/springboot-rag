# Task 11 Report - Scope documents/search/ask/compare to active project + group toggle

**Commit:** 9a9b0e7
**Files modified:** `src/main/resources/static/index.html`, `src/main/resources/static/app.js`

---

## Changes made

### index.html
- Added `<style>` block in `<head>` for `.group-toggle` class (modeled after existing `.hl-toggle`, with `margin-left: auto` to right-align it in the flex scope-bar).
- Added `<label class="group-toggle" id="group-toggle-q" hidden>` with `<input type="checkbox" id="group-search-q">` inside `#query-scope`.
- Added `<label class="group-toggle" id="group-toggle-c" hidden>` with `<input type="checkbox" id="group-search-c">` inside `#compare-scope`.
- IDs use `-q` / `-c` suffixes to avoid duplicate-ID violation; both labels contain `group-search` so `grep -c group-search` returns 2.

### app.js (globals added)
- `let activeProjectGroup = null` - groupName of the active project, or null.
- `let groupSearchEnabled = false` - shared toggle state.
- `let projectsCache = []` - full project list, stored on each `loadProjects()` call, used by the select-change handler to look up the group name without a round trip.

### Step 1 - Document routes scoped
- `refreshDocs()`: `fetch('/documents')` -> `projectFetch('/documents')`.
- Delete button in `refreshDocs()`: `fetch('/documents/${id}', {DELETE})` -> `projectFetch('/documents/' + id, {DELETE})`.
- `showChunkView()`: `fetch('/documents/${id}/chunks')` -> `projectFetch('/documents/' + id + '/chunks')`.
- `uploadFile()` XHR: `xhr.open('POST', '/documents')` -> `xhr.open('POST', '/projects/' + activeProjectId + '/documents')`. Note: XHR cannot use `projectFetch` (which returns a Promise), so the URL is constructed directly.

### Step 2 - Search + Compare + Chat scoped
- `appendScope(url)`: now also appends `&projectId=<activeProjectId>&group=<groupSearchEnabled>`. Both `/search` and `/compare` go through this helper, so both are covered.
- Chat POST body: added `projectId: Number(activeProjectId)` and `group: groupSearchEnabled`.

### Step 3 - Group toggle
- `renderScopeChips()` visibility condition changed: `allDocIds.length > 1` -> `allDocIds.length > 1 || !!activeProjectGroup`. This ensures the scope bar stays visible (and shows the toggle) even when a project has 0 or 1 documents if it belongs to a group.
- `updateGroupToggleVisibility()` added: shows/hides both toggle labels, updates the group name span inside each label, syncs checked state with `groupSearchEnabled`, then calls `renderScopeChips()` to re-evaluate bar visibility.
- Event listeners on `#group-search-q` and `#group-search-c` keep both in sync and update `groupSearchEnabled`.
- `loadProjects()`: stores `activeProjectGroup = target.groupName || null` and calls `updateGroupToggleVisibility()` after `refreshDocs()`.
- Select-change handler: looks up group from `projectsCache`, resets `groupSearchEnabled = false`, unchecks both checkboxes, calls `updateGroupToggleVisibility()`.

---

## Curl verification outputs

### projectFetch('/documents') in served app.js
```
curl -s http://localhost:8085/app.js | grep -c "projectFetch('/documents')"
1  (pass, >= 1 required)
```

### group-search elements in served index.html
```
curl -s http://localhost:8085/ | grep -c group-search
2  (pass, expected 2)
```

### Isolation test (projects A=3, B=4, both in group ZApp)

Upload doc `a.md` to A, doc `b.md` to B:
```
POST /projects/3/documents  ->  {"docId":"a","chunksStored":1}
POST /projects/4/documents  ->  {"docId":"b","chunksStored":1}
```

Project-scoped doc lists:
```
GET /projects/3/documents  ->  [{"docId":"a","sourceFile":"a.md","chunkCount":1}]
GET /projects/4/documents  ->  [{"docId":"b","sourceFile":"b.md","chunkCount":1}]
```
Each project sees only its own document - isolation confirmed.

Search scoped to A only (`group=false`):
```
GET /search?q=content&type=fts&projectId=3&group=false
-> [{"docId":"a", "content":"# A\n\nalpha frontend content", "score":0.061}]
```
Only A's chunk returned.

Search over whole ZApp group (`group=true`):
```
GET /search?q=content&type=fts&projectId=3&group=true
-> [{"docId":"b", "content":"# B\n\nbeta backend content", "score":0.061},
    {"docId":"a", "content":"# A\n\nalpha frontend content", "score":0.061}]
```
Both A and B chunks returned (group fan-out working).

Cleanup: `DELETE /projects/3` and `DELETE /projects/4` - both 200, DB restored.

### Node syntax check
```
node --check src/main/resources/static/app.js
(no output = clean)
```

---

## Deviations from brief

1. **Toggle IDs use suffixes** - brief wrote `id="group-search"` for both checkboxes. Duplicate IDs are invalid HTML so `-q` / `-c` suffixes were used (`group-search-q`, `group-search-c`). The grep check `grep -c group-search` still returns 2 because both contain the substring.

2. **Scope bar visibility extended** - `renderScopeChips()` now shows the scope bar when `activeProjectGroup` is non-null even with 0-1 docs. This is required so the group toggle is visible before a second document is added to a grouped project. No other behavior changed.

3. **XHR upload uses string concatenation** - `projectFetch` wraps `fetch` and returns a Promise; XHR cannot use it. The URL is built as `'/projects/' + activeProjectId + '/documents'` which is equivalent.

---

## Browser-only concerns

- **Static file cache**: The browser may serve a stale `app.js` / `index.html` if cached. A hard refresh (Ctrl+Shift+R) or cache-busting query param may be needed after deploy. The Spring Boot dev server (`mvnw spring-boot:run`) typically serves static files without long-lived cache headers so this is only a concern in production.
- **`scopeInitialized` across project switches**: On first load `scopeInitialized` is `false`; after first `refreshDocs`, it is `true`. Subsequent project switches reuse the initialized state but `syncScope` correctly adds all new-project doc IDs to `selectedScope` and drops removed IDs, so effective scope resets to "all" on every switch.
- **`Number(activeProjectId)` in chat body**: `activeProjectId` is always a string (per Task 10 contract). `Number()` converts it safely; if the string is ever `null` this would send `0` which the backend may reject - acceptable risk since `activeProjectId` is set before any chat request can be made.

---

## Review fix - null active-project guards + CSS move

**Applied from review findings (2026-07-04)**

### Fix 1 - Guard `refreshDocs` against non-OK response (important)
`app.js` line 376: added `if (!res.ok) return;` immediately after `const res = await projectFetch('/documents');`. Prevents a JSON parse crash when `activeProjectId` is null (empty DB) or the project route 404s.

### Fix 2 - Null-guard `appendScope` (minor)
`app.js` line 430: added `if (!activeProjectId) return url;` as first line of `appendScope()`. Prevents `&projectId=null` being sent to /search and /compare when no project is selected. Existing scope/group appending logic is untouched.

### Fix 3 - Guard chat submit with no active project (minor)
`app.js` line 840: added `if (!activeProjectId) { toast('Select or create a project first', 'error'); return; }` between the `if (!q) return;` guard and `$('#chat-q').value = ''`. Closes the `Number(null)->0` path in the chat POST body.

### Fix 4 - Move `.group-toggle` CSS out of inline `<style>` block (minor)
- Removed the entire `<style>` block from `index.html` `<head>` (it contained only the three `.group-toggle` rules).
- Added identical rules to `style.css` after the `.scope-chip.on::before` rule (scope-bar section).
- `<style>` count in index.html is now 0.

### Verification
- `node --check src/main/resources/static/app.js` -> no output (pass)
- `grep -c 'group-toggle' style.css` -> 3 (pass, >= 1 required)
- `<style` count in index.html -> 0 (inline block fully removed)
