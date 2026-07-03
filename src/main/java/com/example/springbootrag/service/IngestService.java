package com.example.springbootrag.service;

import com.example.springbootrag.chunk.Chunk;
import com.example.springbootrag.chunk.MarkdownChunker;
import com.example.springbootrag.chunk.WordWindowChunker;
import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.repository.PgVectorRepository;
import com.example.springbootrag.repository.QdrantRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutionException;

@Service
public class IngestService {

    private final EmbeddingProvider embeddings;
    private final PgVectorRepository pgVector;
    private final QdrantRepository qdrant;
    private final ProjectService projectService;
    private final WordWindowChunker wordWindow = new WordWindowChunker(120, 20);
    private final MarkdownChunker markdown = new MarkdownChunker(300, new WordWindowChunker(120, 20));

    public IngestService(EmbeddingProvider embeddings,
                         PgVectorRepository pgVector,
                         QdrantRepository qdrant,
                         ProjectService projectService) {
        this.embeddings = embeddings;
        this.pgVector = pgVector;
        this.qdrant = qdrant;
        this.projectService = projectService;
    }

    // ---- Legacy wrappers (resolve default project) ----------------------------------------

    /** Raw-text ingest via the default project. */
    public int ingest(String docId, String text) {
        return ingest(projectService.defaultProjectId(), docId, text);
    }

    /** Markdown ingest via the default project. */
    public int ingestMarkdown(String docId, String sourceFile, String markdownText) {
        return ingestMarkdown(projectService.defaultProjectId(), docId, sourceFile, markdownText);
    }

    /** Delete via the default project. */
    public void delete(String docId) {
        delete(projectService.defaultProjectId(), docId);
    }

    // ---- Project-scoped methods -----------------------------------------------------------

    /** Raw-text ingest: word-window chunking, no metadata. */
    public int ingest(long projectId, String docId, String text) {
        return ingestChunks(projectId, docId, null, wordWindow.chunk(text));
    }

    /** Markdown file ingest: structure-aware chunking with heading breadcrumbs. */
    public int ingestMarkdown(long projectId, String docId, String sourceFile, String markdownText) {
        return ingestChunks(projectId, docId, sourceFile, markdown.chunk(markdownText));
    }

    /**
     * Upsert-by-project+doc: clear any existing chunks for this project/docId first so
     * re-ingesting the same document replaces it instead of accumulating duplicates.
     */
    public int ingestChunks(long projectId, String docId, String sourceFile, List<Chunk> chunks) {
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("docId is required");
        }
        delete(projectId, docId);
        for (Chunk chunk : chunks) {
            float[] vec = embeddings.embed(chunk.text());
            long id = pgVector.insert(projectId, docId, chunk.position(), chunk.text(),
                    sourceFile, chunk.headingPath(), vec);
            try {
                qdrant.upsert(id, projectId, docId, chunk.position(), chunk.text(),
                        sourceFile, chunk.headingPath(), vec);
            } catch (ExecutionException | InterruptedException e) {
                throw new IllegalStateException("Qdrant upsert failed", e);
            }
        }
        return chunks.size();
    }

    public void delete(long projectId, String docId) {
        pgVector.deleteByDocId(projectId, docId);
        try {
            qdrant.deleteByDocId(projectId, docId);
        } catch (ExecutionException | InterruptedException e) {
            throw new IllegalStateException("Qdrant delete failed", e);
        }
    }
}
