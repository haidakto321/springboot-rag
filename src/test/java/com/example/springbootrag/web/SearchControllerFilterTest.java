package com.example.springbootrag.web;

import com.example.springbootrag.repository.MetadataFilter;
import com.example.springbootrag.security.CurrentUser;
import com.example.springbootrag.security.SecurityConfig;
import com.example.springbootrag.security.TestContexts;
import com.example.springbootrag.service.ProjectService;
import com.example.springbootrag.service.SearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SearchController.class)
@Import(SecurityConfig.class)
@WithMockUser
class SearchControllerFilterTest {

    @Autowired MockMvc mvc;
    @MockBean SearchService searchService;
    @MockBean ProjectService projects;
    @MockBean CurrentUser currentUser;

    @BeforeEach
    void stubs() {
        when(currentUser.context()).thenReturn(TestContexts.PUBLIC);
        when(projects.resolveScope(any(), anyBoolean())).thenReturn(List.of(1L));
        when(searchService.search(any(), anyString(), anyString(), anyInt(), anyList(), anyList(),
                any(MetadataFilter.class))).thenReturn(List.of());
    }

    private static boolean anyBoolean() {
        return org.mockito.ArgumentMatchers.anyBoolean();
    }

    @Test
    void filtersParamIsParsedAndPassedThrough() throws Exception {
        mvc.perform(get("/search")
                        .param("q", "late payment")
                        .param("docType", "invoice")
                        .param("filters", """
                                {"filters":[{"path":"values.customer","op":"eq","value":"ACME"}]}"""))
                .andExpect(status().isOk());

        ArgumentCaptor<MetadataFilter> captor = ArgumentCaptor.forClass(MetadataFilter.class);
        verify(searchService).search(any(), eq("hybrid"), eq("late payment"), eq(10),
                anyList(), anyList(), captor.capture());

        assertThat(captor.getValue().docType()).isEqualTo("invoice");
        assertThat(captor.getValue().conditions()).hasSize(1);
        assertThat(captor.getValue().conditions().get(0).path()).isEqualTo("values.customer");
    }

    @Test
    void docTypeAloneIsEnough() throws Exception {
        mvc.perform(get("/search").param("q", "anything").param("docType", "contract"))
                .andExpect(status().isOk());

        ArgumentCaptor<MetadataFilter> captor = ArgumentCaptor.forClass(MetadataFilter.class);
        verify(searchService).search(any(), anyString(), anyString(), anyInt(),
                anyList(), anyList(), captor.capture());

        assertThat(captor.getValue().docType()).isEqualTo("contract");
        assertThat(captor.getValue().isEmpty()).isFalse();
    }

    @Test
    void noFilterParamsMeansAnEmptyFilterNotNull() throws Exception {
        mvc.perform(get("/search").param("q", "anything")).andExpect(status().isOk());

        ArgumentCaptor<MetadataFilter> captor = ArgumentCaptor.forClass(MetadataFilter.class);
        verify(searchService).search(any(), anyString(), anyString(), anyInt(),
                anyList(), anyList(), captor.capture());

        // Empty, never null: "no filter" must not become a predicate that matches nothing.
        assertThat(captor.getValue()).isNotNull();
        assertThat(captor.getValue().isEmpty()).isTrue();
    }

    @Test
    void malformedFilterJsonIsABadRequest() throws Exception {
        mvc.perform(get("/search").param("q", "x").param("filters", "{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownFilterOpIsABadRequest() throws Exception {
        mvc.perform(get("/search").param("q", "x").param("filters", """
                        {"filters":[{"path":"values.a","op":"regex","value":"x"}]}"""))
                .andExpect(status().isBadRequest());
    }
}
