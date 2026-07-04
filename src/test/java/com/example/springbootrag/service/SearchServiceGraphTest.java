package com.example.springbootrag.service;

import com.example.springbootrag.config.GraphProperties;
import com.example.springbootrag.config.RerankProperties;
import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.repository.DocEdgeRepository;
import com.example.springbootrag.repository.PgFtsRepository;
import com.example.springbootrag.repository.PgVectorRepository;
import com.example.springbootrag.repository.QdrantRepository;
import com.example.springbootrag.rerank.IdentityReranker;
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

        // hybrid seed = one hit in doc A
        when(fts.search(anyString(), anyInt(), anyList(), anyList()))
                .thenReturn(List.of(hit(1, "A", null)));
        when(vec.search(any(float[].class), anyInt(), anyList(), anyList()))
                .thenReturn(List.of(hit(1, "A", null)));
        // A links to B
        when(edges.neighbors(anyLong(), eq(List.of("A")))).thenReturn(List.of("B"));
        // neighbor pull returns a chunk from B
        when(vec.chunksByDocIds(anyLong(), eq(List.of("B"))))
                .thenReturn(List.of(hit(2, "B", null)));

        GraphProperties gp = new GraphProperties();
        RerankProperties rp = new RerankProperties();

        SearchService svc = new SearchService(embed, fts, vec, qdrant,
                new IdentityReranker(), rp, edges, gp);

        List<SearchHit> out = svc.search("graph", "q", 10, List.of(1L), List.of());
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

        Instant older = Instant.parse("2024-01-01T00:00:00Z");
        Instant newer = Instant.parse("2024-06-01T00:00:00Z");

        // seed = single hit in doc S
        SearchHit seedHit = new SearchHit(1, "S", 0, "seed", "S.md", null, 0.9, older);
        when(fts.search(anyString(), anyInt(), anyList(), anyList())).thenReturn(List.of(seedHit));
        when(vec.search(any(float[].class), anyInt(), anyList(), anyList())).thenReturn(List.of(seedHit));

        // S links to two neighbor docs whose chunks carry an EQUAL rerank score - a true tie.
        when(edges.neighbors(anyLong(), eq(List.of("S")))).thenReturn(List.of("OLD", "NEW"));
        SearchHit oldTieHit = new SearchHit(2, "OLD", 0, "old but equal score", "OLD.md", null, 0.5, older);
        SearchHit newTieHit = new SearchHit(3, "NEW", 0, "new and equal score", "NEW.md", null, 0.5, newer);
        when(vec.chunksByDocIds(anyLong(), eq(List.of("OLD", "NEW"))))
                .thenReturn(List.of(oldTieHit, newTieHit));

        GraphProperties gp = new GraphProperties();
        RerankProperties rp = new RerankProperties();

        SearchService svc = new SearchService(embed, fts, vec, qdrant,
                new IdentityReranker(), rp, edges, gp);

        List<SearchHit> out = svc.search("graph", "q", 10, List.of(1L), List.of());

        int oldIdx = indexOfDoc(out, "OLD");
        int newIdx = indexOfDoc(out, "NEW");
        assertThat(newIdx).as("equal-score candidates: newer updatedAt should sort first").isLessThan(oldIdx);
    }

    @Test
    void relevanceDominatesOverRecency() {
        EmbeddingProvider embed = mock(EmbeddingProvider.class);
        when(embed.embed(anyString())).thenReturn(new float[768]);

        PgFtsRepository fts = mock(PgFtsRepository.class);
        PgVectorRepository vec = mock(PgVectorRepository.class);
        QdrantRepository qdrant = mock(QdrantRepository.class);
        DocEdgeRepository edges = mock(DocEdgeRepository.class);

        Instant older = Instant.parse("2024-01-01T00:00:00Z");
        Instant newer = Instant.parse("2024-06-01T00:00:00Z");

        // seed hit S is relevant to BOTH keyword and vector search (agreement -> higher RRF score)
        // and is older than the neighbor chunk below.
        SearchHit seedHit = new SearchHit(1, "S", 0, "relevant seed", "S.md", null, 0.9, older);
        when(fts.search(anyString(), anyInt(), anyList(), anyList())).thenReturn(List.of(seedHit));
        when(vec.search(any(float[].class), anyInt(), anyList(), anyList())).thenReturn(List.of(seedHit));

        // Neighbor chunk is recent but carries a much LOWER relevance score than the seed hit.
        when(edges.neighbors(anyLong(), eq(List.of("S")))).thenReturn(List.of("RECENT"));
        SearchHit recentLowRelevance = new SearchHit(2, "RECENT", 0, "recent, low relevance",
                "RECENT.md", null, 0.01, newer);
        when(vec.chunksByDocIds(anyLong(), eq(List.of("RECENT"))))
                .thenReturn(List.of(recentLowRelevance));

        GraphProperties gp = new GraphProperties();
        RerankProperties rp = new RerankProperties();

        SearchService svc = new SearchService(embed, fts, vec, qdrant,
                new IdentityReranker(), rp, edges, gp);

        List<SearchHit> out = svc.search("graph", "q", 10, List.of(1L), List.of());

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
