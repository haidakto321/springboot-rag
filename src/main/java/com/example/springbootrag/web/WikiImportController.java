package com.example.springbootrag.web;

import com.example.springbootrag.service.ProjectService;
import com.example.springbootrag.tool.WikiImporter;
import com.example.springbootrag.web.dto.WikiImportRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Bulk-imports a local Azure-wiki clone into a project, streaming live progress as
 * newline-delimited JSON frames (mirrors ChatController):
 *   {"type":"start","total":N}          - once, before the first page
 *   {"type":"progress","done":k,"total":N,"doc":...}  - after each page ingested
 *   {"type":"done","pagesImported":N}   - normal completion
 *   {"type":"error","message":...}      - failure mid-stream (response already 200)
 * Bad requests (missing project / bad path) fail fast with 400 before the stream starts.
 *
 * SECURITY: reads an arbitrary server-side directory path from the caller. Acceptable only for a
 * localhost single-user dev sandbox with no auth. See docs/implementation-notes.md.
 */
@RestController
public class WikiImportController {

    private final WikiImporter wikiImporter;
    private final ProjectService projectService;
    private final ObjectMapper mapper;

    public WikiImportController(WikiImporter wikiImporter, ProjectService projectService, ObjectMapper mapper) {
        this.wikiImporter = wikiImporter;
        this.projectService = projectService;
        this.mapper = mapper;
    }

    @PostMapping(value = "/projects/{projectId}/import-wiki", produces = "application/x-ndjson")
    public StreamingResponseBody importWiki(@PathVariable long projectId, @RequestBody WikiImportRequest req) {
        // Validate before the stream starts so bad input fails fast as a clean 400.
        if (!projectService.exists(projectId)) {
            throw new IllegalArgumentException("project not found: " + projectId);
        }
        if (req == null || req.path() == null || req.path().isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        Path wikiRoot = Path.of(req.path().strip());
        if (!Files.isDirectory(wikiRoot)) {
            throw new IllegalArgumentException("path does not exist or is not a directory");
        }
        return out -> {
            boolean[] started = {false};
            int[] total = {0};
            int[] failed = {0};
            try {
                int imported = wikiImporter.importDir(projectId, wikiRoot, new WikiImporter.ProgressListener() {
                    private void ensureStarted(int count) {
                        total[0] = count;
                        if (!started[0]) {
                            writeFrame(out, Map.of("type", "start", "total", count));
                            started[0] = true;
                        }
                    }
                    @Override public void onPage(int done, int count, String doc) {
                        ensureStarted(count);
                        writeFrame(out, Map.of("type", "progress", "done", done, "total", count, "doc", doc));
                    }
                    @Override public void onError(int done, int count, String doc, Exception e) {
                        ensureStarted(count);
                        failed[0]++;
                        writeFrame(out, Map.of("type", "skip", "done", done, "total", count, "doc", doc,
                                "message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                    }
                });
                if (!started[0]) {
                    // no pages found: still emit a start frame so the client sees the total.
                    writeFrame(out, Map.of("type", "start", "total", total[0]));
                }
                writeFrame(out, Map.of("type", "done",
                        "pagesImported", imported, "pagesFailed", failed[0], "total", total[0]));
            } catch (Exception e) {
                // Fatal error (not a per-page skip): response already committed (200), report as a frame.
                writeFrameQuietly(out, Map.of("type", "error",
                        "message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        };
    }

    private void writeFrame(OutputStream out, Map<String, ?> frame) {
        try {
            out.write(mapper.writeValueAsBytes(frame));
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeFrameQuietly(OutputStream out, Map<String, ?> frame) {
        try {
            writeFrame(out, frame);
        } catch (RuntimeException ignored) {
            // client likely disconnected; nothing more we can do
        }
    }
}
