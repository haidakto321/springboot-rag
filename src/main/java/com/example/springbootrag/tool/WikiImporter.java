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

    public WikiImporter(IngestService ingest, DocEdgeRepository docEdges) {
        this.ingest = ingest;
        this.docEdges = docEdges;
    }

    /**
     * Walks {@code wikiRoot} for {@code *.md} pages (skipping {@code .git} and {@code .attachments}),
     * ingests each page with a git-derived {@code updatedAt}, and writes a folder-hierarchy edge
     * from the parent folder's page to each child page. Returns the number of pages imported.
     */
    public int importDir(long projectId, Path wikiRoot) throws Exception {
        int count = 0;
        try (Stream<Path> paths = Files.walk(wikiRoot)) {
            List<Path> pages = paths
                    .filter(p -> p.toString().endsWith(".md"))
                    .filter(p -> !isUnderGitDir(p))
                    .filter(p -> !p.toString().contains(".attachments"))
                    .toList();
            for (Path page : pages) {
                String text = Files.readString(page, StandardCharsets.UTF_8);
                String docId = docIdOf(page);
                Instant updated = gitDate(wikiRoot, wikiRoot.relativize(page).toString());
                ingest.ingestMarkdown(projectId, docId, page.getFileName().toString(), text, updated);
                // hierarchy edge: parent folder page -> this page
                Path parent = page.getParent();
                if (parent != null && !parent.equals(wikiRoot)) {
                    docEdges.insertHierarchy(projectId, docIdOf(parent), docId);
                }
                count++;
            }
        }
        return count;
    }

    /** True if any path segment of {@code p} is a {@code .git} directory. */
    private static boolean isUnderGitDir(Path p) {
        return p.toString().contains(java.io.File.separator + ".git" + java.io.File.separator);
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
