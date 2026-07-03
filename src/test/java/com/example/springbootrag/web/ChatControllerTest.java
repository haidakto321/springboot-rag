package com.example.springbootrag.web;

import com.example.springbootrag.service.ChatService;
import com.example.springbootrag.service.ProjectService;
import com.example.springbootrag.web.dto.AskResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired MockMvc mvc;
    @MockBean ChatService chatService;
    @MockBean ProjectService projectService;

    @BeforeEach
    void setupProjectService() {
        when(projectService.defaultProjectId()).thenReturn(1L);
        when(projectService.resolveScope(anyLong(), anyBoolean())).thenReturn(List.of(1L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamsTokenSourcesAndDoneFrames() throws Exception {
        when(chatService.chatStream(anyList(), anyList(), any(), any())).thenAnswer(inv -> {
            Consumer<String> onToken = inv.getArgument(3);
            onToken.accept("Hi");
            onToken.accept("!");
            return List.of(new AskResponse.Source(1, "doc-a", "# H", 0.9, "chunk text", 4));
        });

        MvcResult started = mvc.perform(post("/chat/stream")
                        .contentType("application/json")
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .contains("\"type\":\"token\"").contains("Hi").contains("!")
                .contains("\"type\":\"sources\"").contains("doc-a")
                .contains("\"type\":\"done\"");
    }

    @Test
    void emptyMessagesIsBadRequest() throws Exception {
        mvc.perform(post("/chat/stream")
                        .contentType("application/json")
                        .content("{\"messages\":[]}"))
                .andExpect(status().isBadRequest());
    }
}
