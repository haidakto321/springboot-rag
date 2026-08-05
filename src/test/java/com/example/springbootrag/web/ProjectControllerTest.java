package com.example.springbootrag.web;

import com.example.springbootrag.service.ProjectService;
import com.example.springbootrag.web.dto.ProjectSummary;
import com.example.springbootrag.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProjectController.class)
@Import(SecurityConfig.class)   // exercise the real policy (stateless Basic, CSRF off), not the slice default
@WithMockUser
class ProjectControllerTest {
    @Autowired MockMvc mvc;
    @MockBean ProjectService svc;

    @Test void createReturnsProject() throws Exception {
        when(svc.create("FE", "MyApp")).thenReturn(7L);
        mvc.perform(post("/projects").contentType("application/json")
                .content("{\"name\":\"FE\",\"groupName\":\"MyApp\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.id").value(7));
    }
    @Test void listReturnsProjects() throws Exception {
        when(svc.list()).thenReturn(List.of(new ProjectSummary(1,"Default",null,2,10)));
        mvc.perform(get("/projects")).andExpect(status().isOk())
           .andExpect(jsonPath("$[0].name").value("Default"));
    }
    @Test void blankNameIsBadRequest() throws Exception {
        when(svc.create(any(), any())).thenThrow(new IllegalArgumentException("project name is required"));
        mvc.perform(post("/projects").contentType("application/json").content("{\"name\":\"\"}"))
           .andExpect(status().isBadRequest());
    }

    @Test void patchRenameOnlyDoesNotClearGroup() throws Exception {
        mvc.perform(patch("/projects/5").contentType("application/json")
                .content("{\"name\":\"NewName\"}"))
           .andExpect(status().isOk());
        verify(svc).rename(5L, "NewName");
        verify(svc, never()).setGroup(anyLong(), any());
    }

    @Test void patchGroupChangeDoesNotRename() throws Exception {
        mvc.perform(patch("/projects/5").contentType("application/json")
                .content("{\"groupName\":\"G\"}"))
           .andExpect(status().isOk());
        verify(svc).setGroup(5L, "G");
        verify(svc, never()).rename(anyLong(), anyString());
    }

    @Test void patchClearsGroupWhenExplicitNull() throws Exception {
        mvc.perform(patch("/projects/5").contentType("application/json")
                .content("{\"groupName\":null}"))
           .andExpect(status().isOk());
        verify(svc).setGroup(5L, null);
    }

    @Test void deleteCallsService() throws Exception {
        mvc.perform(delete("/projects/5"))
           .andExpect(status().isOk());
        verify(svc).delete(5L);
    }

    @Test void groupsReturnsList() throws Exception {
        when(svc.groups()).thenReturn(List.of("MyApp"));
        mvc.perform(get("/groups"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0]").value("MyApp"));
    }
}
