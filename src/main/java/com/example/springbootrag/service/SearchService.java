package com.example.springbootrag.service;

import com.example.springbootrag.config.RerankProperties;
import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.fusion.RrfFusion;
import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.rerank.Reranker;
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
    private final Reranker reranker;
    private final RerankProperties rerankProps;
    private final RrfFusion rrf = new RrfFusion(60);

    public SearchService(EmbeddingProvider embeddings,
                         PgFtsRepository fts,
                         PgVectorRepository pgVector,
                         QdrantRepository qdrant,
                         Reranker reranker,
                         RerankProperties rerankProps) {
        this.embeddings = embeddings;
        this.fts = fts;
        this.pgVector = pgVector;
        this.qdrant = qdrant;
        this.reranker = reranker;
        this.rerankProps = rerankProps;
    }

    private static final int MAX_TOP_K = 100;

    // ---- Legacy overloads (no project filter) ----------------------------------------

    public List<SearchHit> search(String type, String query, int topK) {
        return search(type, query, topK, List.of(), List.of());
    }

    /** Scopes by docIds only; empty = all documents across all projects. */
    public List<SearchHit> search(String type, String query, int topK, List<String> docIds) {
        return search(type, query, topK, List.of(), docIds);
    }

    public Map<String, BackendResult> compare(String query, int topK) {
        return compare(query, topK, List.of(), List.of());
    }

    public Map<String, BackendResult> compare(String query, int topK, List<String> docIds) {
        return compare(query, topK, List.of(), docIds);
    }

    // ---- Primary project-scoped overloads -------------------------------------------

    /**
     * Searches using the given backend type, optionally scoped by projectIds and docIds.
     * Empty list for either means that filter is absent.
     */
    public List<SearchHit> search(String type, String query, int topK,
                                  List<Long> projectIds, List<String> docIds) {
        validateTopK(topK);
        return switch (type) {
            case "fts" -> fts.search(query, topK, projectIds, docIds);
            case "pgvector" -> pgVector.search(embeddings.embed(query), topK, projectIds, docIds);
            case "qdrant" -> qdrantSearch(embeddings.embed(query), topK, projectIds, docIds);
            case "hybrid" -> hybrid(query, embeddings.embed(query), topK, projectIds, docIds);
            case "rerank" -> rerank(query, embeddings.embed(query), topK, projectIds, docIds);
            default -> throw new IllegalArgumentException("unknown type: " + type);
        };
    }

    /**
     * Runs every backend once for the same query and returns timing + results per backend.
     * The query is embedded a SINGLE time and the resulting vector is shared.
     */
    public Map<String, BackendResult> compare(String query, int topK,
                                              List<Long> projectIds, List<String> docIds) {
        validateTopK(topK);
        float[] qvec = embeddings.embed(query);

        Map<String, BackendResult> out = new LinkedHashMap<>();
        out.put("fts", timed(() -> fts.search(query, topK, projectIds, docIds)));
        out.put("pgvector", timed(() -> pgVector.search(qvec, topK, projectIds, docIds)));
        out.put("qdrant", timed(() -> qdrantSearch(qvec, topK, projectIds, docIds)));
        out.put("hybrid", timed(() -> hybrid(query, qvec, topK, projectIds, docIds)));
        out.put("rerank", timed(() -> rerank(query, qvec, topK, projectIds, docIds)));
        return out;
    }

    // ---- Private helpers -------------------------------------------------------------

    private BackendResult timed(java.util.function.Supplier<List<SearchHit>> backend) {
        long start = System.nanoTime();
        List<SearchHit> hits = backend.get();
        long ms = (System.nanoTime() - start) / 1_000_000;
        return new BackendResult(hits, ms);
    }

    private List<SearchHit> hybrid(String query, float[] queryEmbedding, int topK,
                                   List<Long> projectIds, List<String> docIds) {
        List<SearchHit> keyword = fts.search(query, topK, projectIds, docIds);
        List<SearchHit> vector = pgVector.search(queryEmbedding, topK, projectIds, docIds);
        return rrf.fuse(List.of(keyword, vector), topK);
    }

    private List<SearchHit> rerank(String query, float[] queryEmbedding, int topK,
                                   List<Long> projectIds, List<String> docIds) {
        List<SearchHit> candidates = hybrid(query, queryEmbedding, rerankProps.getCandidates(), projectIds, docIds);
        return reranker.rerank(query, candidates, topK);
    }

    private List<SearchHit> qdrantSearch(float[] queryEmbedding, int topK,
                                          List<Long> projectIds, List<String> docIds) {
        try {
            return qdrant.search(queryEmbedding, topK, projectIds, docIds);
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
