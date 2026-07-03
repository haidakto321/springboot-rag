package com.example.springbootrag.web;

import com.example.springbootrag.model.DocumentSummary;
import com.example.springbootrag.repository.PgVectorRepository;
import com.example.springbootrag.service.IngestService;
import com.example.springbootrag.service.ProjectService;
import com.example.springbootrag.web.dto.ChunkView;
import com.example.springbootrag.web.dto.IngestResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
public class DocumentController {

    private static final long MAX_BYTES = 2 * 1024 * 1024;

    private final IngestService ingestService;
    private final PgVectorRepository pgVector;
    private final ProjectService projectService;

    public DocumentController(IngestService ingestService,
                              PgVectorRepository pgVector,
                              ProjectService projectService) {
        this.ingestService = ingestService;
        this.pgVector = pgVector;
        this.projectService = projectService;
    }

    // ---- Legacy endpoints (scoped to Default project) ------------------------------------

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestResponse upload(@RequestParam("file") MultipartFile file) {
        UploadResult u = parseUpload(file);
        int stored = ingestService.ingestMarkdown(u.docId(), u.sourceFile(), u.text());
        return new IngestResponse(u.docId(), stored);
    }

    @GetMapping("/documents")
    public List<DocumentSummary> list() {
        return pgVector.listDocuments(projectService.defaultProjectId());
    }

    @GetMapping("/documents/{docId}/chunks")
    public List<ChunkView> chunks(@PathVariable String docId) {
        return pgVector.listChunks(projectService.defaultProjectId(), docId);
    }

    @DeleteMapping("/documents/{docId}")
    public void delete(@PathVariable String docId) {
        ingestService.delete(docId);
    }

    // ---- Project-scoped endpoints --------------------------------------------------------

    @PostMapping(value = "/projects/{projectId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestResponse uploadToProject(@PathVariable long projectId,
                                          @RequestParam("file") MultipartFile file) {
        if (!projectService.exists(projectId)) throw new IllegalArgumentException("project not found: " + projectId);
        UploadResult u = parseUpload(file);
        int stored = ingestService.ingestMarkdown(projectId, u.docId(), u.sourceFile(), u.text());
        return new IngestResponse(u.docId(), stored);
    }

    @GetMapping("/projects/{projectId}/documents")
    public List<DocumentSummary> listForProject(@PathVariable long projectId) {
        return pgVector.listDocuments(projectId);
    }

    @DeleteMapping("/projects/{projectId}/documents/{docId}")
    public void deleteFromProject(@PathVariable long projectId, @PathVariable String docId) {
        ingestService.delete(projectId, docId);
    }

    @GetMapping("/projects/{projectId}/documents/{docId}/chunks")
    public List<ChunkView> chunksForProject(@PathVariable long projectId,
                                            @PathVariable String docId) {
        return pgVector.listChunks(projectId, docId);
    }

    // ---- Shared upload logic -------------------------------------------------------------

    /**
     * Validates and decodes the uploaded file.
     * Throws {@link IllegalArgumentException} on wrong extension, oversized content, or invalid UTF-8.
     */
    private UploadResult parseUpload(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".md")) {
            throw new IllegalArgumentException("only .md files are accepted");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("file too large (max 2 MB)");
        }
        String text = decodeUtf8(file);
        String docId = sanitizeDocId(name);
        return new UploadResult(docId, name, text);
    }

    private record UploadResult(String docId, String sourceFile, String text) {}

    /* Strict UTF-8 decode: malformed bytes are a client error, not replacement chars. */
    private static String decodeUtf8(MultipartFile file) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(file.getBytes()))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("file is not valid UTF-8");
        } catch (IOException e) {
            throw new IllegalStateException("could not read upload", e);
        }
    }

    /* "My Notes.md" -> "My-Notes". Same name re-upload replaces the document. */
    private static String sanitizeDocId(String filename) {
        String base = filename.substring(0, filename.length() - ".md".length());
        return base.replaceAll("[^a-zA-Z0-9._-]", "-");
    }
}
