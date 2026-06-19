package com.example.springbootrag.service;

import com.example.springbootrag.config.RerankProperties;
import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.rerank.FakeReranker;
import com.example.springbootrag.repository.PgFtsRepository;
import com.example.springbootrag.repository.PgVectorRepository;
import com.example.springbootrag.repository.QdrantRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchServiceRerankTest {

    private SearchHit hit(long id) {
        return new SearchHit(id, "doc", (int) id, "c" + id, 0.0);
    }

    @Test
    void rerankPathReordersHybridCandidates() {
        EmbeddingProvider embeddings = mock(EmbeddingProvider.class);
        PgFtsRepository fts = mock(PgFtsRepository.class);
        PgVectorRepository pgVector = mock(PgVectorRepository.class);
        QdrantRepository qdrant = mock(QdrantRepository.class);

        when(embeddings.embed("q")).thenReturn(new float[]{0.1f});
        // Same list from both arms so hybrid order is deterministic: 1,2,3
        when(fts.search("q", 50)).thenReturn(List.of(hit(1), hit(2), hit(3)));
        when(pgVector.search(new float[]{0.1f}, 50)).thenReturn(List.of(hit(1), hit(2), hit(3)));

        RerankProperties props = new RerankProperties();
        props.setCandidates(50);

        SearchService service = new SearchService(embeddings, fts, pgVector, qdrant,
                new FakeReranker(), props);

        List<SearchHit> out = service.search("rerank", "q", 3);

        // FakeReranker reverses hybrid's order
        assertThat(out).extracting(SearchHit::id).containsExactly(3L, 2L, 1L);
    }
}
