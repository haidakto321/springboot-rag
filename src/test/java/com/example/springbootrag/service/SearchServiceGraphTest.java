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
}
