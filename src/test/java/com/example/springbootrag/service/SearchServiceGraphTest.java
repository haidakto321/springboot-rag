package com.example.springbootrag.service;

import com.example.springbootrag.config.GraphProperties;
import com.example.springbootrag.config.RerankProperties;
import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.graph.EntityExtractor;
import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.repository.DocEdgeRepository;
import com.example.springbootrag.repository.EntityRepository;
import com.example.springbootrag.repository.PgFtsRepository;
import com.example.springbootrag.repository.PgVectorRepository;
import com.example.springbootrag.repository.QdrantRepository;
import com.example.springbootrag.rerank.IdentityReranker;
import com.example.springbootrag.security.SearchContext;
import com.example.springbootrag.security.TestContexts;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SearchServiceGraphTest {

    private SearchHit hit(long id, String doc, Instant updated) {
        return new SearchHit(id, doc, 0, "content " + id, doc + ".md", null, 0.9, updated);
    }

    @Test
    void graphExpandsToLinkedNeighborDocs() {
        EmbeddingProvider embed = mock(EmbeddingProvider.class);
        when(embed.embed(anyString())).thenReturn(new float[768]);

        PgFtsRepository fts = mock(PgFtsRepository.class);
        PgVectorRepository vec = mock(PgVectorRepository.class);
        QdrantRepository qdrant = mock(QdrantRepository.class);
        DocEdgeRepository edges = mock(DocEdgeRepository.class);
        EntityExtractor entityExtractor = mock(EntityExtractor.class);
        EntityRepository entityRepo = mock(EntityRepository.class);

        // hybrid seed = one hit in doc A
        when(fts.search(any(SearchContext.class), anyString(), anyInt(), anyList(), anyList()))
                .thenReturn(List.of(hit(1, "A", null)));
        when(vec.search(any(SearchContext.class), any(float[].class), anyInt(), anyList(), anyList()))
                .thenReturn(List.of(hit(1, "A", null)));
        // A links to B
        when(edges.neighbors(anyLong(), eq(List.of("A")))).thenReturn(List.of("B"));
        // neighbor pull returns a chunk from B
        when(vec.chunksByDocIds(any(SearchContext.class), anyLong(), eq(List.of("B"))))
                .thenReturn(List.of(hit(2, "B", null)));

        GraphProperties gp = new GraphProperties();
        RerankProperties rp = new RerankProperties();

        SearchService svc = new SearchService(embed, fts, vec, qdrant,
                new IdentityReranker(), rp, edges, gp, entityExtractor, entityRepo);

        List<SearchHit> out = svc.search(TestContexts.PUBLIC, "graph", "q", 10, List.of(1L), List.of());
        assertThat(out).extracting(SearchHit::docId).contains("A", "B");
    }

    @Test
    void recencyOnlyBreaksTiesBetweenEqualRerankScores() {
        EmbeddingProvider embed = mock(EmbeddingProvider.class);
        when(embed.embed(anyString())).thenReturn(new float[768]);

        PgFtsRepository fts = mock(PgFtsRepository.class);
        PgVectorRepository vec = mock(PgVectorRepository.class);
        QdrantRepository qdrant = mock(QdrantRepository.class);
        DocEdgeRepository edges = mock(DocEdgeRepository.class);
        EntityExtractor entityExtractor = mock(EntityExtractor.class);
        EntityRepository entityRepo = mock(EntityRepository.class);

        Instant oldest = Instant.parse("2024-01-01T00:00:00Z");
        Instant middle = Instant.parse("2024-03-01T00:00:00Z");
        Instant newest = Instant.parse("2024-06-01T00:00:00Z");

        // seed = single hit in doc S (irrelevant to the assertions below)
        SearchHit seedHit = new SearchHit(1, "S", 0, "seed", "S.md", null, 0.05, oldest);
        when(fts.search(any(SearchContext.class), anyString(), anyInt(), anyList(), anyList())).thenReturn(List.of(seedHit));
        when(vec.search(any(SearchContext.class), any(float[].class), anyInt(), anyList(), anyList())).thenReturn(List.of(seedHit));

        // S links to three neighbor docs:
        //  - HIGH: high rerank score but OLDER updatedAt than TIE_B - a pure-recency primary
        //    sort would rank HIGH behind TIE_B despite HIGH being far more relevant.
        //  - TIE_A / TIE_B: a genuine equal (low) rerank score - recency must break this tie,
        //    with TIE_B (newer) sorting before TIE_A (older).
        when(edges.neighbors(anyLong(), eq(List.of("S")))).thenReturn(List.of("HIGH", "TIE_A", "TIE_B"));
        SearchHit highHit = new SearchHit(2, "HIGH", 0, "high relevance, older", "HIGH.md", null, 0.9, middle);
        SearchHit tieAHit = new SearchHit(3, "TIE_A", 0, "low relevance, oldest", "TIE_A.md", null, 0.1, oldest);
        SearchHit tieBHit = new SearchHit(4, "TIE_B", 0, "low relevance, newest", "TIE_B.md", null, 0.1, newest);
        when(vec.chunksByDocIds(any(SearchContext.class), anyLong(), eq(List.of("HIGH", "TIE_A", "TIE_B"))))
                .thenReturn(List.of(highHit, tieAHit, tieBHit));

        GraphProperties gp = new GraphProperties();
        RerankProperties rp = new RerankProperties();

        SearchService svc = new SearchService(embed, fts, vec, qdrant,
                new IdentityReranker(), rp, edges, gp, entityExtractor, entityRepo);

        List<SearchHit> out = svc.search(TestContexts.PUBLIC, "graph", "q", 10, List.of(1L), List.of());

        int highIdx = indexOfDoc(out, "HIGH");
        int tieAIdx = indexOfDoc(out, "TIE_A");
        int tieBIdx = indexOfDoc(out, "TIE_B");

        // Relevance dominates: HIGH must outrank both TIE candidates even though HIGH is
        // older than TIE_B. Under a pure-recency-primary sort (the old bug), TIE_B (newest
        // overall) would be placed ahead of HIGH despite its far lower score - so this
        // assertion fails under the old behavior and passes under the new one.
        assertThat(highIdx)
                .as("higher rerank score must outrank both equal-score ties, even though HIGH is older than TIE_B")
                .isLessThan(tieAIdx)
                .isLessThan(tieBIdx);

        // Among the genuine score tie, recency breaks it: TIE_B (newer) sorts before TIE_A (older).
        assertThat(tieBIdx).as("equal-score candidates: newer updatedAt should sort first").isLessThan(tieAIdx);
    }

    @Test
    void relevanceDominatesOverRecency() {
        EmbeddingProvider embed = mock(EmbeddingProvider.class);
        when(embed.embed(anyString())).thenReturn(new float[768]);

        PgFtsRepository fts = mock(PgFtsRepository.class);
        PgVectorRepository vec = mock(PgVectorRepository.class);
        QdrantRepository qdrant = mock(QdrantRepository.class);
        DocEdgeRepository edges = mock(DocEdgeRepository.class);
        EntityExtractor entityExtractor = mock(EntityExtractor.class);
        EntityRepository entityRepo = mock(EntityRepository.class);

        Instant older = Instant.parse("2024-01-01T00:00:00Z");
        Instant newer = Instant.parse("2024-06-01T00:00:00Z");

        // seed hit S is relevant to BOTH keyword and vector search (agreement -> higher RRF score)
        // and is older than the neighbor chunk below.
        SearchHit seedHit = new SearchHit(1, "S", 0, "relevant seed", "S.md", null, 0.9, older);
        when(fts.search(any(SearchContext.class), anyString(), anyInt(), anyList(), anyList())).thenReturn(List.of(seedHit));
        when(vec.search(any(SearchContext.class), any(float[].class), anyInt(), anyList(), anyList())).thenReturn(List.of(seedHit));

        // Neighbor chunk is recent but carries a much LOWER relevance score than the seed hit.
        when(edges.neighbors(anyLong(), eq(List.of("S")))).thenReturn(List.of("RECENT"));
        SearchHit recentLowRelevance = new SearchHit(2, "RECENT", 0, "recent, low relevance",
                "RECENT.md", null, 0.01, newer);
        when(vec.chunksByDocIds(any(SearchContext.class), anyLong(), eq(List.of("RECENT"))))
                .thenReturn(List.of(recentLowRelevance));

        GraphProperties gp = new GraphProperties();
        RerankProperties rp = new RerankProperties();

        SearchService svc = new SearchService(embed, fts, vec, qdrant,
                new IdentityReranker(), rp, edges, gp, entityExtractor, entityRepo);

        List<SearchHit> out = svc.search(TestContexts.PUBLIC, "graph", "q", 10, List.of(1L), List.of());

        assertThat(out.get(0).docId())
                .as("higher-relevance older seed must not be outranked by a recent low-relevance neighbor")
                .isEqualTo("S");
    }

    private static int indexOfDoc(List<SearchHit> hits, String docId) {
        for (int i = 0; i < hits.size(); i++) {
            if (hits.get(i).docId().equals(docId)) {
                return i;
            }
        }
        throw new AssertionError("docId not found in results: " + docId);
    }
}
