package com.example.springbootrag.service;

import com.example.springbootrag.chat.ChatProvider;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SearchServiceSemanticTest {

    private SearchHit hit(long id, String doc) {
        return new SearchHit(id, doc, 0, "c" + id, doc + ".md", null, 0.5, null);
    }

    @Test
    void semanticExpansionPullsOrphanChunkViaSharedEntity() {
        EmbeddingProvider embed = mock(EmbeddingProvider.class);
        when(embed.embed(anyString())).thenReturn(new float[768]);
        PgFtsRepository fts = mock(PgFtsRepository.class);
        PgVectorRepository vec = mock(PgVectorRepository.class);
        QdrantRepository qdrant = mock(QdrantRepository.class);
        DocEdgeRepository edges = mock(DocEdgeRepository.class);
        EntityRepository entities = mock(EntityRepository.class);
        ChatProvider chat = mock(ChatProvider.class);

        // seed hybrid = a chunk in the well-known doc A
        when(fts.search(any(SearchContext.class), anyString(), anyInt(), anyList(), anyList())).thenReturn(List.of(hit(1, "A")));
        when(vec.search(any(SearchContext.class), any(float[].class), anyInt(), anyList(), anyList())).thenReturn(List.of(hit(1, "A")));
        when(edges.neighbors(anyLong(), anyList())).thenReturn(List.of());   // no structural link (orphan)

        // query mentions PaymentsService -> matches entity 10 -> chunk 99 lives in orphan doc B
        when(chat.chat(anyString(), anyString()))
                .thenReturn("{\"entities\":[{\"name\":\"PaymentsService\",\"type\":\"service\"}],\"relations\":[]}");
        when(entities.matchEntityIds(anyLong(), anyList(), anyInt())).thenReturn(List.of(10L));
        when(entities.neighborEntityIds(anyLong(), eq(List.of(10L)))).thenReturn(List.of());
        when(entities.chunkIdsForEntities(anyList())).thenReturn(List.of(99L));
        when(vec.chunksByIds(any(SearchContext.class), eq(List.of(99L)))).thenReturn(List.of(hit(99, "B")));

        GraphProperties gp = new GraphProperties();
        gp.setEdges("both");
        SearchService svc = new SearchService(embed, fts, vec, qdrant, new IdentityReranker(),
                new RerankProperties(), edges, gp,
                new EntityExtractor(chat, ""), entities);

        List<SearchHit> out = svc.search(TestContexts.PUBLIC, "graph", "who owns PaymentsService", 10, List.of(1L), List.of());
        assertThat(out).extracting(SearchHit::docId).contains("A", "B");   // orphan B reconnected
    }
}
