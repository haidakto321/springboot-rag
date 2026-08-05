package com.example.springbootrag.graph;

import com.example.springbootrag.chat.ChatProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EntityExtractorTest {

    @Test
    void parsesEntitiesAndRelationsFromModelJson() {
        ChatProvider chat = mock(ChatProvider.class);
        when(chat.chat(anyString(), anyString())).thenReturn("""
            {"entities":[{"name":"PaymentsService","type":"service"},
                         {"name":"Alice","type":"team"}],
             "relations":[{"src":"Alice","rel":"owns","dst":"PaymentsService"}]}
            """);

        EntityExtractor ex = new EntityExtractor(chat, "");
        ExtractedGraph g = ex.extract("Alice owns the PaymentsService.");

        assertThat(g.entities()).extracting(ExtractedGraph.Entity::name)
                .containsExactlyInAnyOrder("PaymentsService", "Alice");
        assertThat(g.relations()).hasSize(1);
        assertThat(g.relations().get(0).rel()).isEqualTo("owns");
    }

    @Test
    void returnsEmptyOnGarbageModelOutput() {
        ChatProvider chat = mock(ChatProvider.class);
        when(chat.chat(anyString(), anyString())).thenReturn("sorry I cannot do that");

        EntityExtractor ex = new EntityExtractor(chat, "");
        ExtractedGraph g = ex.extract("whatever");

        assertThat(g.entities()).isEmpty();
        assertThat(g.relations()).isEmpty();
    }
}
