package com.example.springbootrag.service;

import com.example.springbootrag.chunk.Chunker;
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
    private final Chunker chunker = new Chunker(120, 20);

    public IngestService(EmbeddingProvider embeddings,
                         PgVectorRepository pgVector,
                         QdrantRepository qdrant) {
        this.embeddings = embeddings;
        this.pgVector = pgVector;
        this.qdrant = qdrant;
    }

    public int ingest(String docId, String text) {
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("docId is required");
        }
        // Upsert-by-doc: clear any existing chunks for this docId first so re-ingesting
        // the same document replaces it instead of silently accumulating duplicates.
        delete(docId);
        List<String> chunks = chunker.chunk(text);
        int index = 0;
        for (String chunk : chunks) {
            float[] vec = embeddings.embed(chunk);
            long id = pgVector.insert(docId, index, chunk, vec);
            try {
                qdrant.upsert(id, docId, index, chunk, vec);
            } catch (ExecutionException | InterruptedException e) {
                throw new IllegalStateException("Qdrant upsert failed", e);
            }
            index++;
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
