package com.example.springbootrag.web;

import com.example.springbootrag.security.Roles;
import com.example.springbootrag.security.SecurityConfig;
import com.example.springbootrag.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The role gate on the one endpoint that destroys a whole project, and the premise the rest of the
 * delete authorisation rests on.
 *
 * <p>Deleting a project cascades its quarantine pen, which holds the only copy of every document
 * in it, so this endpoint is at least as destructive as {@code discard} - and until now it carried
 * no check of any kind.
 *
 * <p>The anonymous test is not ceremony. {@code DeleteGuard} lets a call with no principal through,
 * on the grounds that only server-side callers can be principal-less; that is true exactly as long
 * as the filter chain refuses anonymous requests. A later {@code permitAll} would turn the guard
 * into a bypass with every other test still green.
 */
@WebMvcTest(ProjectController.class)
@Import(SecurityConfig.class)   // the real policy, including @EnableMethodSecurity
class ProjectControllerSecurityTest {

    private static final String DELETE_PROJECT = "/projects/1";

    @Autowired MockMvc mvc;
    @Autowired UserDetailsService directory;

    @MockBean ProjectService projects;

    private List<String> authoritiesOf(String username) {
        return directory.loadUserByUsername(username).getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    @WithMockUser(username = "haiks", authorities = {"GROUP_public", "GROUP_eng"})
    void deletingAProjectWithoutTheRoleIs403AndNeverReachesTheService() throws Exception {
        mvc.perform(delete(DELETE_PROJECT)).andExpect(status().isForbidden());

        verify(projects, never()).delete(anyLong());
    }

    @Test
    @WithMockUser(username = "alice", authorities = {"GROUP_public", "ROLE_project-delete"})
    void deletingAProjectWithTheRoleIsAllowedThrough() throws Exception {
        mvc.perform(delete(DELETE_PROJECT)).andExpect(status().isOk());

        verify(projects).delete(1L);
    }

    @Test
    void anAnonymousDeleteIsRejectedByTheFilterChainBeforeAnyController() throws Exception {
        // The premise behind DeleteGuard's "no principal means a server-side call" branch.
        mvc.perform(delete(DELETE_PROJECT)).andExpect(status().isUnauthorized());

        verify(projects, never()).delete(anyLong());
    }

    @Test
    void theConfiguredUsersMatchTheRoleTheCodeChecksFor() {
        // Binds application.yml to the constant. Without this, `roles: [project-delete]` could be
        // deleted or typo'd and every test would still pass while nobody could delete a project.
        assertThat(authoritiesOf("alice")).contains(Roles.PREFIX + Roles.PROJECT_DELETE);
        assertThat(authoritiesOf("haiks")).doesNotContain(Roles.PREFIX + Roles.PROJECT_DELETE);
    }
}
