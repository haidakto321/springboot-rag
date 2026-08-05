package com.example.springbootrag.web;

import com.example.springbootrag.security.CurrentUser;
import com.example.springbootrag.security.SecurityConfig;
import com.example.springbootrag.security.TestContexts;
import com.example.springbootrag.trace.RagTrace;
import com.example.springbootrag.trace.TraceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TraceController.class)
@Import(SecurityConfig.class)
@WithMockUser
class TraceControllerTest {

    @Autowired MockMvc mvc;
    @MockBean TraceRepository repo;
    @MockBean CurrentUser currentUser;

    @BeforeEach
    void identity() {
        when(currentUser.context()).thenReturn(TestContexts.PUBLIC);
    }

    private static RagTrace sample() {
        return new RagTrace(UUID.randomUUID(), Instant.EPOCH, "test-public", List.of(5L),
                "raw question", "condensed question", "rerank",
                List.of(new RagTrace.Retrieved("runbook", 2, 0.9)),
                Map.of("total", 120L), 300, 90, "answer [1]", "cited");
    }

    @Test
    void readsOnlyTheCallersOwnTraces() throws Exception {
        when(repo.recent("test-public", 10)).thenReturn(List.of(sample()));

        mvc.perform(get("/traces"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].rawQuery").value("raw question"))
           .andExpect(jsonPath("$[0].condensedQuery").value("condensed question"))
           .andExpect(jsonPath("$[0].retrieved[0].docId").value("runbook"))
           .andExpect(jsonPath("$[0].stageLatencyMs.total").value(120));

        // The principal comes from the SecurityContext, never from a request parameter.
        verify(repo).recent(eq("test-public"), anyInt());
    }

    @Test
    void aPrincipalParameterCannotBeSuppliedByTheCaller() throws Exception {
        mvc.perform(get("/traces").param("principal", "alice"))
           .andExpect(status().isOk());

        verify(repo).recent(eq("test-public"), anyInt());
        verify(repo, never()).recent(eq("alice"), anyInt());
    }

    @Test
    void outOfRangeLimitIsBadRequest() throws Exception {
        mvc.perform(get("/traces").param("limit", "500"))
           .andExpect(status().isBadRequest());
        verify(repo, never()).recent(eq("test-public"), anyInt());
    }

    @Test
    void unauthenticatedIsRejected() throws Exception {
        SecurityContextHolder.clearContext();
        mvc.perform(get("/traces").with(org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.anonymous()))
           .andExpect(status().isUnauthorized());
    }
}
