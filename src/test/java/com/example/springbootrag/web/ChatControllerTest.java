package com.example.springbootrag.web;

import com.example.springbootrag.guard.AnswerGuard;
import com.example.springbootrag.security.CurrentUser;
import com.example.springbootrag.security.SecurityConfig;
import com.example.springbootrag.security.TestContexts;
import com.example.springbootrag.service.ChatService;
import com.example.springbootrag.service.ProjectService;
import com.example.springbootrag.web.dto.AskResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
@Import(SecurityConfig.class)   // exercise the real policy (stateless Basic, CSRF off), not the slice default
@WithMockUser   // the filter chain is real now; without an identity every call is a 401
class ChatControllerTest {

    @Autowired MockMvc mvc;
    @MockBean ChatService chatService;
    @MockBean ProjectService projectService;
    @MockBean CurrentUser currentUser;

    @BeforeEach
    void setupProjectService() {
        when(projectService.defaultProjectId()).thenReturn(1L);
        when(projectService.resolveScope(any(), anyBoolean())).thenReturn(List.of(1L));
        when(currentUser.context()).thenReturn(TestContexts.PUBLIC);
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamsTokenSourcesAndDoneFrames() throws Exception {
        when(chatService.chatStream(any(), anyList(), anyList(), any(), anyBoolean(), any(), any())).thenAnswer(inv -> {
            Consumer<String> onToken = inv.getArgument(5);
            onToken.accept("Hi");
            onToken.accept("!");
            return new ChatService.StreamOutcome(
                    List.of(new AskResponse.Source(1, "doc-a", "# H", 0.9, "chunk text", 4)),
                    new AnswerGuard.Verdict(true, "cited", "Hi!"));
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

        verify(chatService).chatStream(eq(TestContexts.PUBLIC), anyList(), eq(List.of(1L)), any(), anyBoolean(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUngroundedStreamedAnswerEmitsAGuardFrame() throws Exception {
        // Tokens cannot be recalled once streamed, so the client is told instead.
        when(chatService.chatStream(any(), anyList(), anyList(), any(), anyBoolean(), any(), any())).thenAnswer(inv -> {
            Consumer<String> onToken = inv.getArgument(5);
            onToken.accept("the admin recovery code is hunter2");
            return new ChatService.StreamOutcome(List.of(),
                    new AnswerGuard.Verdict(false, "ungrounded", AnswerGuard.REFUSAL));
        });

        MvcResult started = mvc.perform(post("/chat/stream")
                        .contentType("application/json")
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"type\":\"guard\"").contains("ungrounded");
    }

    @Test
    void emptyMessagesIsBadRequest() throws Exception {
        mvc.perform(post("/chat/stream")
                        .contentType("application/json")
                        .content("{\"messages\":[]}"))
                .andExpect(status().isBadRequest());
    }
}
