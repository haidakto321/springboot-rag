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
    private final WordWindowChunker wordWindow = new WordWindowChunker(120, 20);
    private final MarkdownChunker markdown = new MarkdownChunker(300, new WordWindowChunker(120, 20));

    public IngestService(EmbeddingProvider embeddings,
                         PgVectorRepository pgVector,
                         QdrantRepository qdrant) {
        this.embeddings = embeddings;
        this.pgVector = pgVector;
        this.qdrant = qdrant;
    }

    /** Raw-text ingest (existing JSON endpoint): word-window chunking, no metadata. */
    public int ingest(String docId, String text) {
        return ingestChunks(docId, null, wordWindow.chunk(text));
    }

    /** Markdown file ingest: structure-aware chunking with heading breadcrumbs. */
    public int ingestMarkdown(String docId, String sourceFile, String markdownText) {
        return ingestChunks(docId, sourceFile, markdown.chunk(markdownText));
    }

    /**
     * Upsert-by-doc: clear any existing chunks for this docId first so re-ingesting
     * the same document replaces it instead of silently accumulating duplicates.
     */
    public int ingestChunks(String docId, String sourceFile, List<Chunk> chunks) {
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("docId is required");
        }
        delete(docId);
        for (Chunk chunk : chunks) {
            float[] vec = embeddings.embed(chunk.text());
            long id = pgVector.insert(docId, chunk.position(), chunk.text(),
                    sourceFile, chunk.headingPath(), vec);
            try {
                qdrant.upsert(id, docId, chunk.position(), chunk.text(),
                        sourceFile, chunk.headingPath(), vec);
            } catch (ExecutionException | InterruptedException e) {
                throw new IllegalStateException("Qdrant upsert failed", e);
            }
        }
        return chunks.size();
    }

    public void delete(String docId) {
        pgVector.deleteByDocId(docId);
        try {
            qdrant.deleteByDocId(docId);
        } catch (ExecutionException | InterruptedException e) {
            throw new IllegalStateException("Qdrant delete failed", e);
        }
    }
}
