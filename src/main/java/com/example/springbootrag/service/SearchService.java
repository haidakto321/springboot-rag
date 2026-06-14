package com.example.springbootrag.service;

import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.fusion.RrfFusion;
import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.repository.PgFtsRepository;
import com.example.springbootrag.repository.PgVectorRepository;
import com.example.springbootrag.repository.QdrantRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Service
public class SearchService {

    private final EmbeddingProvider embeddings;
    private final PgFtsRepository fts;
    private final PgVectorRepository pgVector;
    private final QdrantRepository qdrant;
    private final RrfFusion rrf = new RrfFusion(60);

    public SearchService(EmbeddingProvider embeddings,
                         PgFtsRepository fts,
                         PgVectorRepository pgVector,
                         QdrantRepository qdrant) {
        this.embeddings = embeddings;
        this.fts = fts;
        this.pgVector = pgVector;
        this.qdrant = qdrant;
    }

    private static final int MAX_TOP_K = 100;

    public List<SearchHit> search(String type, String query, int topK) {
        validateTopK(topK);
        // Embed only for the vector-based backends, and only once per call.
        return switch (type) {
            case "fts" -> fts.search(query, topK);
            case "pgvector" -> pgVector.search(embeddings.embed(query), topK);
            case "qdrant" -> qdrantSearch(embeddings.embed(query), topK);
            case "hybrid" -> hybrid(query, embeddings.embed(query), topK);
            default -> throw new IllegalArgumentException("unknown type: " + type);
        };
    }

    /**
     * Runs every backend once for the same query and returns timing + results per backend.
     * The query is embedded a SINGLE time and the resulting vector is shared by pgvector,
     * qdrant, and hybrid - so the timings reflect search cost, not three Ollama round-trips.
     */
    public Map<String, BackendResult> compare(String query, int topK) {
        validateTopK(topK);
        float[] qvec = embeddings.embed(query);

        Map<String, BackendResult> out = new LinkedHashMap<>();
        out.put("fts", timed(() -> fts.search(query, topK)));
        out.put("pgvector", timed(() -> pgVector.search(qvec, topK)));
        out.put("qdrant", timed(() -> qdrantSearch(qvec, topK)));
        out.put("hybrid", timed(() -> hybrid(query, qvec, topK)));
        return out;
    }

    private BackendResult timed(java.util.function.Supplier<List<SearchHit>> backend) {
        long start = System.nanoTime();
        List<SearchHit> hits = backend.get();
        long ms = (System.nanoTime() - start) / 1_000_000;
        return new BackendResult(hits, ms);
    }

    private List<SearchHit> hybrid(String query, float[] queryEmbedding, int topK) {
        List<SearchHit> keyword = fts.search(query, topK);
        List<SearchHit> vector = pgVector.search(queryEmbedding, topK);
        return rrf.fuse(List.of(keyword, vector), topK);
    }

    private List<SearchHit> qdrantSearch(float[] queryEmbedding, int topK) {
        try {
            return qdrant.search(queryEmbedding, topK);
        } catch (ExecutionException | InterruptedException e) {
            throw new IllegalStateException("Qdrant search failed", e);
        }
    }

    private static void validateTopK(int topK) {
        if (topK < 1 || topK > MAX_TOP_K) {
            throw new IllegalArgumentException("topK must be between 1 and " + MAX_TOP_K);
        }
    }

    public record BackendResult(List<SearchHit> hits, long elapsedMs) {}
}
