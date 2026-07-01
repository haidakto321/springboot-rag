package com.example.springbootrag.web;

import com.example.springbootrag.model.DocumentSummary;
import com.example.springbootrag.repository.PgVectorRepository;
import com.example.springbootrag.service.IngestService;
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

    public DocumentController(IngestService ingestService, PgVectorRepository pgVector) {
        this.ingestService = ingestService;
        this.pgVector = pgVector;
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestResponse upload(@RequestParam("file") MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".md")) {
            throw new IllegalArgumentException("only .md files are accepted");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("file too large (max 2 MB)");
        }
        String text = decodeUtf8(file);
        String docId = sanitizeDocId(name);
        int stored = ingestService.ingestMarkdown(docId, name, text);
        return new IngestResponse(docId, stored);
    }

    @GetMapping("/documents")
    public List<DocumentSummary> list() {
        return pgVector.listDocuments();
    }

    @DeleteMapping("/documents/{docId}")
    public void delete(@PathVariable String docId) {
        ingestService.delete(docId);
    }

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
