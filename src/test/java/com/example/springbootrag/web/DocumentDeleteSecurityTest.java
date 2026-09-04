package com.example.springbootrag.web;

import com.example.springbootrag.repository.PgVectorRepository;
import com.example.springbootrag.security.CurrentUser;
import com.example.springbootrag.security.SecurityConfig;
import com.example.springbootrag.service.IngestService;
import com.example.springbootrag.service.ProjectService;
import com.example.springbootrag.service.QuarantineService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a refused document delete looks like over HTTP.
 *
 * <p>The decision itself is {@code DeleteGuard}'s and is tested against a real database in
 * {@code DeleteAuthorizationIntegrationTest}, which calls the service as a bean and so cannot see a
 * status code. README promises a {@code 403}; that promise lives entirely in Spring Security's
 * translation of {@link AccessDeniedException}, which one catch-all {@code @ExceptionHandler} in
 * {@link GlobalExceptionHandler} would silently turn into a 500 with every other test still green.
 * This is the same gap {@code QuarantineControllerSecurityTest} was written to close for release.
 */
@WebMvcTest(DocumentController.class)
@Import(SecurityConfig.class)
class DocumentDeleteSecurityTest {

    private static final String DELETE_DOC = "/projects/1/documents/Salary-Bands";

    @Autowired MockMvc mvc;

    @MockBean IngestService ingestService;
    @MockBean PgVectorRepository pgVector;
    @MockBean ProjectService projectService;
    @MockBean CurrentUser currentUser;
    @MockBean QuarantineService quarantineService;

    @Test
    @WithMockUser(username = "haiks", authorities = {"GROUP_public", "GROUP_eng"})
    void aDeleteRefusedByTheGuardSurfacesAs403AndNotAsAServerError() throws Exception {
        doThrow(new AccessDeniedException("cannot delete document 'Salary-Bands'"))
                .when(ingestService).delete(anyLong(), anyString());

        mvc.perform(delete(DELETE_DOC)).andExpect(status().isForbidden());
    }

    @Test
    void anAnonymousDocumentDeleteIsRejectedByTheFilterChainBeforeAnyController() throws Exception {
        // The premise behind DeleteGuard letting principal-less calls through.
        mvc.perform(delete(DELETE_DOC)).andExpect(status().isUnauthorized());

        verify(ingestService, never()).delete(anyLong(), anyString());
    }
}
