package com.example.springbootrag.service;

import com.example.springbootrag.config.GraphProperties;
import com.example.springbootrag.config.RerankProperties;
import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.fusion.RrfFusion;
import com.example.springbootrag.graph.EntityExtractor;
import com.example.springbootrag.graph.ExtractedGraph;
import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.rerank.Reranker;
import com.example.springbootrag.repository.DocEdgeRepository;
import com.example.springbootrag.repository.EntityRepository;
import com.example.springbootrag.repository.PgFtsRepository;
import com.example.springbootrag.repository.MetadataFilter;
import com.example.springbootrag.repository.PgVectorRepository;
import com.example.springbootrag.repository.QdrantRepository;
import com.example.springbootrag.security.SearchContext;
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
    private final DocEdgeRepository docEdges;
    private final GraphProperties graphProps;
    private final EntityExtractor entityExtractor;
    private final EntityRepository entityRepo;
    private final RrfFusion rrf = new RrfFusion(60);

    public SearchService(EmbeddingProvider embeddings,
                         PgFtsRepository fts,
                         PgVectorRepository pgVector,
                         QdrantRepository qdrant,
                         Reranker reranker,
                         RerankProperties rerankProps,
                         DocEdgeRepository docEdges,
                         GraphProperties graphProps,
                         EntityExtractor entityExtractor,
                         EntityRepository entityRepo) {
        this.embeddings = embeddings;
        this.fts = fts;
        this.pgVector = pgVector;
        this.qdrant = qdrant;
        this.reranker = reranker;
        this.rerankProps = rerankProps;
        this.docEdges = docEdges;
        this.graphProps = graphProps;
        this.entityExtractor = entityExtractor;
        this.entityRepo = entityRepo;
    }

    private static final int MAX_TOP_K = 100;

    // ---- Convenience overloads (still identity-bound) --------------------------------

    public List<SearchHit> search(SearchContext ctx, String type, String query, int topK) {
        return search(ctx, type, query, topK, List.of(), List.of());
    }

    /** Scopes by docIds only; empty = every document the caller may read. */
    public List<SearchHit> search(SearchContext ctx, String type, String query, int topK, List<String> docIds) {
        return search(ctx, type, query, topK, List.of(), docIds);
    }

    public Map<String, BackendResult> compare(SearchContext ctx, String query, int topK) {
        return compare(ctx, query, topK, List.of(), List.of());
    }

    public Map<String, BackendResult> compare(SearchContext ctx, String query, int topK, List<String> docIds) {
        return compare(ctx, query, topK, List.of(), docIds);
    }

    // ---- Primary project-scoped overloads -------------------------------------------

    /**
     * Searches using the given backend type, optionally scoped by projectIds and docIds.
     * Empty list for either optional filter means that filter is absent.
     *
     * <p>{@code ctx} is not one filter among several: projectIds and docIds come from the browser
     * and can only NARROW the result set, while the access labels in ctx come from the
     * authenticated principal and always apply. There is deliberately no overload without it.
     */
    public List<SearchHit> search(SearchContext ctx, String type, String query, int topK,
                                  List<Long> projectIds, List<String> docIds) {
        return search(ctx, type, query, topK, projectIds, docIds, MetadataFilter.none());
    }

    /**
     * Same, additionally narrowed by structured record metadata.
     *
     * <p>{@code filter} is a caller preference and can only narrow, like projectIds and docIds.
     * It composes with the access labels in {@code ctx}, never replaces them.
     */
    public List<SearchHit> search(SearchContext ctx, String type, String query, int topK,
                                  List<Long> projectIds, List<String> docIds,
                                  MetadataFilter filter) {
        validateTopK(topK);
        return switch (type) {
            case "fts" -> fts.search(ctx, query, topK, projectIds, docIds, filter);
            case "pgvector" -> pgVector.search(ctx, embeddings.embed(query), topK, projectIds, docIds, filter);
            case "qdrant" -> qdrantSearch(ctx, embeddings.embed(query), topK, projectIds, docIds, filter);
            case "hybrid" -> hybrid(ctx, query, embeddings.embed(query), topK, projectIds, docIds, filter);
            case "rerank" -> rerank(ctx, query, embeddings.embed(query), topK, projectIds, docIds, filter);
            case "graph" -> graph(ctx, query, embeddings.embed(query), topK, projectIds, docIds, filter);
            default -> throw new IllegalArgumentException("unknown type: " + type);
        };
    }

    /**
     * Same as {@link #search(SearchContext, String, String, int, List, List)} but also reports how
     * long each stage took, for {@code rag_trace}.
     *
     * <p>Separate method rather than a parameter on the hot path: nothing that only observes should
     * be able to change what retrieval returns, and the six existing call sites stay untouched.
     */
    public TracedSearch searchTraced(SearchContext ctx, String type, String query, int topK,
                                     List<Long> projectIds, List<String> docIds) {
        return searchTraced(ctx, type, query, topK, projectIds, docIds, MetadataFilter.none());
    }

    /** Traced search, additionally narrowed by structured record metadata. */
    public TracedSearch searchTraced(SearchContext ctx, String type, String query, int topK,
                                     List<Long> projectIds, List<String> docIds,
                                     MetadataFilter filter) {
        validateTopK(topK);
        Map<String, Long> stages = new LinkedHashMap<>();
        long t0 = System.nanoTime();
        float[] qvec = needsEmbedding(type) ? embeddings.embed(query) : null;
        long afterEmbed = System.nanoTime();
        stages.put("embed", msSince(t0, afterEmbed));

        List<SearchHit> hits = switch (type) {
            case "fts" -> fts.search(ctx, query, topK, projectIds, docIds, filter);
            case "pgvector" -> pgVector.search(ctx, qvec, topK, projectIds, docIds, filter);
            case "qdrant" -> qdrantSearch(ctx, qvec, topK, projectIds, docIds, filter);
            case "hybrid" -> hybrid(ctx, query, qvec, topK, projectIds, docIds, filter);
            case "rerank" -> rerank(ctx, query, qvec, topK, projectIds, docIds, filter);
            case "graph" -> graph(ctx, query, qvec, topK, projectIds, docIds, filter);
            default -> throw new IllegalArgumentException("unknown type: " + type);
        };
        stages.put("retrieve", msSince(afterEmbed, System.nanoTime()));
        return new TracedSearch(hits, stages);
    }

    /** Retrieval result plus per-stage milliseconds. */
    public record TracedSearch(List<SearchHit> hits, Map<String, Long> stageLatencyMs) {}

    /** fts is the only backend that never embeds the query. */
    private static boolean needsEmbedding(String type) {
        return !"fts".equals(type);
    }

    private static long msSince(long fromNanos, long toNanos) {
        return (toNanos - fromNanos) / 1_000_000;
    }

    /**
     * Runs every backend once for the same query and returns timing + results per backend.
     * The query is embedded a SINGLE time and the resulting vector is shared.
     */
    public Map<String, BackendResult> compare(SearchContext ctx, String query, int topK,
                                              List<Long> projectIds, List<String> docIds) {
        return compare(ctx, query, topK, projectIds, docIds, MetadataFilter.none());
    }

    /** Same comparison, with one metadata filter applied identically to every backend. */
    public Map<String, BackendResult> compare(SearchContext ctx, String query, int topK,
                                              List<Long> projectIds, List<String> docIds,
                                              MetadataFilter filter) {
        validateTopK(topK);
        float[] qvec = embeddings.embed(query);

        Map<String, BackendResult> out = new LinkedHashMap<>();
        out.put("fts", timed(() -> fts.search(ctx, query, topK, projectIds, docIds, filter)));
        out.put("pgvector", timed(() -> pgVector.search(ctx, qvec, topK, projectIds, docIds, filter)));
        out.put("qdrant", timed(() -> qdrantSearch(ctx, qvec, topK, projectIds, docIds, filter)));
        out.put("hybrid", timed(() -> hybrid(ctx, query, qvec, topK, projectIds, docIds, filter)));
        out.put("rerank", timed(() -> rerank(ctx, query, qvec, topK, projectIds, docIds, filter)));
        out.put("graph", timed(() -> graph(ctx, query, qvec, topK, projectIds, docIds, filter)));
        return out;
    }

    // ---- Private helpers -------------------------------------------------------------

    private BackendResult timed(java.util.function.Supplier<List<SearchHit>> backend) {
        long start = System.nanoTime();
        List<SearchHit> hits = backend.get();
        long ms = (System.nanoTime() - start) / 1_000_000;
        return new BackendResult(hits, ms);
    }

    private List<SearchHit> hybrid(SearchContext ctx, String query, float[] queryEmbedding, int topK,
                                   List<Long> projectIds, List<String> docIds,
                                   MetadataFilter filter) {
        List<SearchHit> keyword = fts.search(ctx, query, topK, projectIds, docIds, filter);
        List<SearchHit> vector = pgVector.search(ctx, queryEmbedding, topK, projectIds, docIds, filter);
        return rrf.fuse(List.of(keyword, vector), topK);
    }

    /**
     * Over-fetches {@code app.rerank.candidates} before trimming to topK. The access filter is
     * applied by the repositories INSIDE that over-fetch, so the cross-encoder never scores - and
     * no debug view can ever print - a chunk the caller may not read.
     */
    private List<SearchHit> rerank(SearchContext ctx, String query, float[] queryEmbedding, int topK,
                                   List<Long> projectIds, List<String> docIds,
                                   MetadataFilter filter) {
        // The over-fetch is ALREADY filtered. Filtering after the trim drops matching documents
        // that the candidate window happened not to reach - silently, and only sometimes.
        List<SearchHit> candidates = hybrid(ctx, query, queryEmbedding, rerankProps.getCandidates(),
                projectIds, docIds, filter);
        return reranker.rerank(query, candidates, topK);
    }

    private List<SearchHit> graph(SearchContext ctx, String query, float[] queryEmbedding, int topK,
                                  List<Long> projectIds, List<String> docIds,
                                  MetadataFilter filter) {
        List<SearchHit> seed = hybrid(ctx, query, queryEmbedding, graphProps.getCandidates(),
                projectIds, docIds, filter);
        if (seed.isEmpty()) {
            return seed;   // fallback: nothing to expand from
        }
        long projectId = projectIds.isEmpty() ? 0L : projectIds.get(0);

        // Union seed chunks with structural neighbor-doc chunks and semantic entity-linked
        // chunks, dedup by chunk id.
        java.util.LinkedHashMap<Long, SearchHit> byId = new java.util.LinkedHashMap<>();
        for (SearchHit h : seed) byId.put(h.id(), h);

        if (structuralOn()) {
            List<String> seedDocs = seed.stream().map(SearchHit::docId).distinct().toList();
            List<String> neighborDocs = docEdges.neighbors(projectId, seedDocs);
            if (!neighborDocs.isEmpty()) {
                // Graph expansion walks doc_edge, which carries no access label - the label check
                // happens here, when the neighbour's chunks are loaded.
                for (SearchHit h : pgVector.chunksByDocIds(ctx, projectId, neighborDocs, filter)) {
                    byId.putIfAbsent(h.id(), h);
                }
            }
        }

        if (semanticOn()) {
            ExtractedGraph qg = entityExtractor.extract(query);
            List<String> qNames = qg.entities().stream()
                    .map(ExtractedGraph.Entity::name).toList();
            List<Long> matched = entityRepo.matchEntityIds(projectId, qNames, graphProps.getMinMentions());
            if (!matched.isEmpty()) {
                List<Long> expanded = new java.util.ArrayList<>(matched);
                expanded.addAll(entityRepo.neighborEntityIds(projectId, matched));
                List<Long> chunkIds = entityRepo.chunkIdsForEntities(expanded);
                for (SearchHit h : pgVector.chunksByIds(ctx, chunkIds, filter)) {
                    byId.putIfAbsent(h.id(), h);
                }
            }
        }

        // Preserve union insertion order (seed hits first in hybrid/RRF order, then
        // structural neighbor-doc chunks, then semantic entity-linked chunks) going into
        // the reranker - relevance ordering must come first so IdentityReranker (the
        // default) does not degrade to recency ordering.
        List<SearchHit> candidates = new java.util.ArrayList<>(byId.values());
        List<SearchHit> ranked = new java.util.ArrayList<>(reranker.rerank(query, candidates, topK));
        // Recency tiebreak ONLY: sort by rerank score desc, then (for true score ties)
        // by updatedAt desc, nulls last. Stable sort keeps non-tied items in place since
        // the reranker output is already score-ordered - recency never overrides relevance.
        ranked.sort(java.util.Comparator.comparingDouble(SearchHit::score).reversed()
                .thenComparing(SearchHit::updatedAt, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        return ranked;
    }

    private List<SearchHit> qdrantSearch(SearchContext ctx, float[] queryEmbedding, int topK,
                                          List<Long> projectIds, List<String> docIds,
                                          MetadataFilter filter) {
        try {
            return qdrant.search(ctx, queryEmbedding, topK, projectIds, docIds, filter);
        } catch (ExecutionException | InterruptedException e) {
            throw new IllegalStateException("Qdrant search failed", e);
        }
    }

    /** True when the graph feature is on and edges is "structural" or "both". */
    private boolean structuralOn() {
        if (!graphProps.isEnabled()) return false;
        String e = graphProps.getEdges();
        return "structural".equalsIgnoreCase(e) || "both".equalsIgnoreCase(e);
    }

    /** True when the graph feature is on and edges is "semantic" or "both". */
    private boolean semanticOn() {
        if (!graphProps.isEnabled()) return false;
        String e = graphProps.getEdges();
        return "semantic".equalsIgnoreCase(e) || "both".equalsIgnoreCase(e);
    }

    private static void validateTopK(int topK) {
        if (topK < 1 || topK > MAX_TOP_K) {
            throw new IllegalArgumentException("topK must be between 1 and " + MAX_TOP_K);
        }
    }

    public record BackendResult(List<SearchHit> hits, long elapsedMs) {}
}
