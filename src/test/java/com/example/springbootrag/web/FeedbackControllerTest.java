package com.example.springbootrag.web;

import com.example.springbootrag.model.FeedbackLabel;
import com.example.springbootrag.repository.FeedbackRepository;
import com.example.springbootrag.repository.PgVectorRepository;
import com.example.springbootrag.security.CurrentUser;
import com.example.springbootrag.security.SecurityConfig;
import com.example.springbootrag.security.SearchContext;
import com.example.springbootrag.security.TestContexts;
import com.example.springbootrag.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FeedbackController.class)
@Import(SecurityConfig.class)   // exercise the real policy (stateless Basic, CSRF off), not the slice default
@WithMockUser
class FeedbackControllerTest {

    @Autowired MockMvc mvc;
    @MockBean FeedbackRepository repo;
    @MockBean ProjectService projects;
    @MockBean PgVectorRepository chunks;
    @MockBean CurrentUser currentUser;

    @BeforeEach
    void projectExists() {
        when(projects.exists(anyLong())).thenReturn(true);
        when(currentUser.context()).thenReturn(TestContexts.PUBLIC);
        when(chunks.isVisible(any(), anyLong(), anyString(), anyInt())).thenReturn(true);
    }

    private static String body(String rating) {
        return """
            {"query":"how to deploy","projectId":5,"docId":"runbook","chunkIndex":3,"rating":"%s"}
            """.formatted(rating);
    }

    @Test void postStoresTheLabel() throws Exception {
        mvc.perform(post("/feedback").contentType("application/json").content(body("up")))
           .andExpect(status().isOk());
        verify(repo).upsert(5L, "how to deploy", "runbook", 3, "up");
    }

    @Test void ratingIsNormalisedToLowerCase() throws Exception {
        mvc.perform(post("/feedback").contentType("application/json").content(body("DOWN")))
           .andExpect(status().isOk());
        verify(repo).upsert(5L, "how to deploy", "runbook", 3, "down");
    }

    @Test void unknownRatingIsBadRequest() throws Exception {
        mvc.perform(post("/feedback").contentType("application/json").content(body("maybe")))
           .andExpect(status().isBadRequest());
        verifyNoInteractions(repo);
    }

    @Test void blankQueryIsBadRequest() throws Exception {
        mvc.perform(post("/feedback").contentType("application/json")
                .content("{\"query\":\"  \",\"projectId\":5,\"docId\":\"d\",\"chunkIndex\":0,\"rating\":\"up\"}"))
           .andExpect(status().isBadRequest());
        verifyNoInteractions(repo);
    }

    @Test void overlongQueryIsBadRequest() throws Exception {
        String longQuery = "x".repeat(FeedbackController.MAX_QUERY + 1);
        mvc.perform(post("/feedback").contentType("application/json")
                .content("{\"query\":\"" + longQuery + "\",\"projectId\":5,\"docId\":\"d\",\"chunkIndex\":0,\"rating\":\"up\"}"))
           .andExpect(status().isBadRequest());
        verifyNoInteractions(repo);
    }

    @Test void negativeChunkIndexIsBadRequest() throws Exception {
        mvc.perform(post("/feedback").contentType("application/json")
                .content("{\"query\":\"q\",\"projectId\":5,\"docId\":\"d\",\"chunkIndex\":-1,\"rating\":\"up\"}"))
           .andExpect(status().isBadRequest());
        verifyNoInteractions(repo);
    }

    @Test void unknownProjectIsBadRequestNotAForeignKeyCrash() throws Exception {
        when(projects.exists(99L)).thenReturn(false);
        mvc.perform(post("/feedback").contentType("application/json")
                .content("{\"query\":\"q\",\"projectId\":99,\"docId\":\"d\",\"chunkIndex\":0,\"rating\":\"up\"}"))
           .andExpect(status().isBadRequest());
        verifyNoInteractions(repo);
    }

    @Test void deleteClearsTheLabel() throws Exception {
        mvc.perform(delete("/feedback")
                .param("query", "how to deploy").param("projectId", "5")
                .param("docId", "runbook").param("chunkIndex", "3"))
           .andExpect(status().isOk());
        verify(repo).clear(5L, "how to deploy", "runbook", 3);
    }

    @Test void listPassesFiltersThrough() throws Exception {
        when(repo.list(any(SearchContext.class), eq(5L), eq("how to deploy"), eq(200))).thenReturn(List.of(
                new FeedbackLabel(1, 5, "how to deploy", "runbook", 3, "up", Instant.EPOCH)));
        mvc.perform(get("/feedback").param("projectId", "5").param("query", "how to deploy"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].docId").value("runbook"))
           .andExpect(jsonPath("$[0].rating").value("up"));
    }

    @Test void blankQueryFilterMeansNoQueryFilter() throws Exception {
        mvc.perform(get("/feedback").param("projectId", "5").param("query", " "))
           .andExpect(status().isOk());
        verify(repo).list(any(SearchContext.class), eq(5L), eq(null), anyInt());
    }

    @Test void outOfRangeLimitIsBadRequest() throws Exception {
        mvc.perform(get("/feedback").param("limit", "5000"))
           .andExpect(status().isBadRequest());
        verify(repo, never()).list(any(), any(), any(), anyInt());
    }
}
