package com.example.springbootrag.web;

import com.example.springbootrag.repository.QuarantineRepository;
import com.example.springbootrag.security.CurrentUser;
import com.example.springbootrag.security.Roles;
import com.example.springbootrag.security.SecurityConfig;
import com.example.springbootrag.service.QuarantineReleaseService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The role gate at the HTTP layer, and the wiring that makes it real.
 *
 * <p>Two things here that the integration test cannot cover. It calls the controller as a bean and
 * asserts {@code AccessDeniedException}, so the **status code** README and ARCHITECTURE both promise
 * is nobody's assertion - a later catch-all {@code @ExceptionHandler} would remap it to 500 with
 * every test still green. And nothing tied the {@code roles:} value in application.yml to
 * {@link Roles#QUARANTINE_RELEASE}, so deleting or misspelling it would 403 every release in the
 * running app while the whole suite passed.
 */
@WebMvcTest(QuarantineController.class)
@Import(SecurityConfig.class)   // the real policy, including @EnableMethodSecurity
class QuarantineControllerSecurityTest {

    private static final String RELEASE = "/projects/1/quarantine/policy/release";
    private static final String DISCARD = "/projects/1/quarantine/policy";

    @Autowired MockMvc mvc;
    @Autowired UserDetailsService directory;

    @MockBean QuarantineReleaseService releaseService;
    @MockBean QuarantineRepository pen;
    @MockBean CurrentUser currentUser;

    private List<String> authoritiesOf(String username) {
        return directory.loadUserByUsername(username).getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    @WithMockUser(username = "haiks", authorities = {"GROUP_public"})
    void releaseWithoutTheRoleIs403AndNeverReachesTheService() throws Exception {
        mvc.perform(post(RELEASE)).andExpect(status().isForbidden());

        verify(releaseService, never()).release(anyLong(), anyString());
    }

    @Test
    @WithMockUser(username = "haiks", authorities = {"GROUP_public"})
    void discardWithoutTheRoleIs403AndNeverReachesTheService() throws Exception {
        mvc.perform(delete(DISCARD)).andExpect(status().isForbidden());

        verify(releaseService, never()).discard(anyLong(), anyString());
    }

    @Test
    @WithMockUser(username = "alice", authorities = {"GROUP_public", "ROLE_quarantine-release"})
    void releaseWithTheRoleIsAllowedThrough() throws Exception {
        mvc.perform(post(RELEASE)).andExpect(status().isOk());

        verify(releaseService).release(1L, "policy");
    }

    @Test
    void theConfiguredUsersMatchTheRoleTheCodeChecksFor() {
        // Binds application.yml to the constant. Without this, `roles: [quarantine-release]` could
        // be deleted or typo'd and every test would still pass while the pen wedged in production.
        assertThat(authoritiesOf("alice")).contains(Roles.PREFIX + Roles.QUARANTINE_RELEASE);
        assertThat(authoritiesOf("haiks")).doesNotContain(Roles.PREFIX + Roles.QUARANTINE_RELEASE);
    }
}
