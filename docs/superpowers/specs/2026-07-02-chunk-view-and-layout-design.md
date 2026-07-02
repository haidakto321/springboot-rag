# Design: Chunk View + Layout Widening

Date: 2026-07-02
Scope: Static frontend + one additive backend endpoint. No dependency changes.

## Motivation

1. On wide monitors the main column (capped at 960px, left-aligned after the 232px
   sidebar) leaves a large empty gutter on the right, which looks broken.
2. No way to inspect what a document was actually chunked into. Useful for a RAG
   debug tool: verify chunking, re-read source, understand what retrieval sees.

## Change 1 - Layout widening (CSS only)

`main` currently: `max-width: 960px`, no auto margins → hugs sidebar, empty right gap.

New: main fills available width up to a ceiling.
- `.main { width: 100%; max-width: 1440px; }` (keep existing padding, left-aligned).
- Sidebar unchanged (232px).
- Fills the gap on typical 1440-1920 screens; capped at 1440px so content does not
  stretch absurdly on ultra-wide monitors.
- No HTML/JS change.

## Change 2 - Chunk view

### Backend (additive)

`chunks` table columns (existing): `id, doc_id, chunk_index, content, source_file,
heading_path, embedding`.

**Repository** - `PgVectorRepository.listChunks(String docId)`:
```sql
SELECT chunk_index, heading_path, content
FROM chunks WHERE doc_id = ? ORDER BY chunk_index
```
Returns `List<ChunkView>`.

**DTO** - new record `com.example.springbootrag.web.dto.ChunkView`:
```java
public record ChunkView(int index, String headingPath, String content) {}
```
`index` = `chunk_index` (0-based, as stored). `headingPath` may be null.

**Controller** - `DocumentController`:
```java
@GetMapping("/documents/{docId}/chunks")
public List<ChunkView> chunks(@PathVariable String docId) {
    return pgVector.listChunks(docId);
}
```
Empty list for unknown docId (no chunks) - no 404 needed; UI shows empty state.

### Frontend

**Doc row actions** - add a `View` button left of `Delete`: `[View][Delete]`.
Same `.btn-delete`-style button minus the red hover (a neutral `.btn-view`).

**Chunk sub-view** - a client-side swap within the Documents screen (nav stays on
Documents; theme/sidebar untouched). Two mutually exclusive views inside
`#screen-docs`:
- `#docs-list-view` - existing stats + import + table (default).
- `#docs-chunk-view` - hidden by default; shown when View is clicked.

`#docs-chunk-view` contents:
- Header row: `← Back to documents` button, `docId` title, `N chunks` meta.
- Chunk rows reuse `.result-row` styling: left column = `[index]` badge +
  `headingPath` (muted); body = `content` in a wrapped monospace block. No score
  column.
- Empty state: quiet "No chunks." line.

**Behavior:**
- Click `View` → `GET /documents/{id}/chunks`, render rows, hide list-view, show
  chunk-view.
- Click `← Back` → show list-view, hide chunk-view (no refetch needed; optional
  `refreshDocs()` to stay current).
- Switching sidebar nav away and back resets to list-view.

### No change to

Search, Ask, upload/progress, theme toggle, delete, stats.

## Testing

- Backend: extend `DocumentIntegrationTest` - upload a doc, `GET
  /documents/{id}/chunks` returns rows with correct `index`/`content`, ordered by
  index; unknown docId returns `[]`.
- Frontend: manual - View shows chunks, Back returns, empty doc shows empty state,
  layout fills wide screen without stretching past 1440px.

## Decisions

- `index` exposed as stored `chunk_index` (0-based), matching ask-source `[index]`
  convention is 1-based there; chunk view uses raw 0-based to reflect DB truth. Fine
  for a debug view.
- Sub-view swap (not modal/drawer) chosen for consistency with existing screen-swap
  pattern and zero overlay CSS.
- No pagination - docs are small (<=2 MB, tens of chunks). YAGNI.
