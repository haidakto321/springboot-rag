package com.example.springbootrag.tool;

import com.example.springbootrag.repository.DocEdgeRepository;
import com.example.springbootrag.service.IngestService;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

/** Bulk-imports a local Azure-wiki git clone into one project. Dev tool, not an HTTP endpoint. */
@Component
public class WikiImporter {

    private final IngestService ingest;
    private final DocEdgeRepository docEdges;
    private final com.example.springbootrag.service.QuarantineService quarantine;

    public WikiImporter(IngestService ingest, DocEdgeRepository docEdges,
                        com.example.springbootrag.service.QuarantineService quarantine) {
        this.ingest = ingest;
        this.docEdges = docEdges;
        this.quarantine = quarantine;
    }

    /** Best effort: the page was readable a moment ago, and a hold must not fail on a re-read. */
    private static String safeRead(Path page) {
        try {
            return Files.readString(page, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** Called during import so callers can report live progress. */
    @FunctionalInterface
    public interface ProgressListener {
        /** After a page is successfully ingested. {@code done} is the running 1..total index. */
        void onPage(int done, int total, String docId);

        /** When a page fails to ingest and is skipped. Default: ignore. */
        default void onError(int done, int total, String docId, Exception e) {}
    }

    /** Backward-compatible overload: no progress reporting, default access label. */
    public int importDir(long projectId, Path wikiRoot) throws Exception {
        return importDir(projectId, wikiRoot, (done, total, docId) -> {});
    }

    /** Overload without an access label: every page gets the configured default group. */
    public int importDir(long projectId, Path wikiRoot, ProgressListener listener) throws Exception {
        return importDir(projectId, wikiRoot, listener, null);
    }

    /**
     * Walks {@code wikiRoot} for {@code *.md} pages (skipping {@code .git} and {@code .attachments}),
     * ingests each page with a git-derived {@code updatedAt}, and writes a folder-hierarchy edge
     * from the parent folder's page to each child page. Returns the number of pages imported.
     * Invokes {@code listener} after each page with the running done/total counts and the doc id.
     */
    public int importDir(long projectId, Path wikiRoot, ProgressListener listener,
                         List<String> allowedGroups) throws Exception {
        int count = 0;
        try (Stream<Path> paths = Files.walk(wikiRoot)) {
            List<Path> pages = paths
                    .filter(p -> p.toString().endsWith(".md"))
                    .filter(p -> !isUnderGitDir(p))
                    .filter(p -> !p.toString().contains(".attachments"))
                    .filter(p -> !isBlankFile(p))   // skip empty pages (wiki stubs, empty conversions)
                    .toList();
            int total = pages.size();
            int index = 0;
            for (Path page : pages) {
                index++;
                String docId = docIdOf(page);
                try {
                    String text = Files.readString(page, StandardCharsets.UTF_8);
                    Instant updated = gitDate(wikiRoot, wikiRoot.relativize(page).toString());
                    ingest.ingestMarkdown(projectId, docId, page.getFileName().toString(), text,
                            updated, allowedGroups);
                    // hierarchy edge: parent folder page -> this page
                    Path parent = page.getParent();
                    if (parent != null && !parent.equals(wikiRoot)) {
                        docEdges.insertHierarchy(projectId, docIdOf(parent), docId);
                    }
                    count++;
                    listener.onPage(index, total, docId);
                } catch (com.example.springbootrag.guard.QuarantineRequiredException e) {
                    // Bulk import is the path most likely to meet a real credential - a runbook
                    // page with a production password. Hold it rather than reporting a generic
                    // failure, so it is visible in the pen and one call from being released.
                    quarantine.hold(projectId, docId, "upload", page.getFileName().toString(), null,
                            safeRead(page), allowedGroups, e.findings());
                    listener.onError(index, total, docId, e);
                } catch (Exception e) {
                    // One bad page must not abort a bulk import; skip it and report.
                    // Roll back any partial chunks written before the failure.
                    try { ingest.delete(projectId, docId); } catch (Exception ignored) {}
                    listener.onError(index, total, docId, e);
                }
            }
        }
        return count;
    }

    /** True if any path segment of {@code p} is a {@code .git} directory. */
    private static boolean isUnderGitDir(Path p) {
        return p.toString().contains(java.io.File.separator + ".git" + java.io.File.separator);
    }

    /** True if the file is empty or whitespace-only. Read errors are left for the ingest path to surface. */
    private static boolean isBlankFile(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8).isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    /* Last path segment, sanitized, like DocumentController/WikiLinkParser. */
    static String docIdOf(Path p) {
        String name = p.getFileName().toString();
        String base = name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
        return base.replaceAll("[^a-zA-Z0-9._-]", "-");
    }

    /* git commit date of the file; falls back to file mtime, then now(). */
    static Instant gitDate(Path repoRoot, String relPath) {
        try {
            Process proc = new ProcessBuilder(
                    "git", "log", "-1", "--format=%cI", "--", relPath)
                    .directory(repoRoot.toFile())
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line = r.readLine();
                proc.waitFor();
                if (line != null && !line.isBlank()) {
                    return Instant.parse(line.trim());
                }
            }
        } catch (Exception ignored) {
            // fall through to mtime
        }
        try {
            return Files.getLastModifiedTime(repoRoot.resolve(relPath)).toInstant();
        } catch (Exception e) {
            return Instant.now();
        }
    }
}
