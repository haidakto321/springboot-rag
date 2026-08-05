package com.example.springbootrag.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentUserTest {

    final CurrentUser currentUser = new CurrentUser();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String name, String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(name, "n/a",
                        List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList()));
    }

    @Test
    void groupsComeFromGroupPrefixedAuthoritiesOnly() {
        authenticate("alice", "GROUP_hr", "GROUP_public", "ROLE_ADMIN");

        SearchContext ctx = currentUser.context();

        assertThat(ctx.principal()).isEqualTo("alice");
        // ROLE_ADMIN is not a group: an unrelated authority must not become read access.
        assertThat(ctx.groups()).containsExactlyInAnyOrder("hr", "public");
    }

    @Test
    void anonymousIsDeniedRatherThanTreatedAsAGrouplessUser() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymous",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThatThrownBy(currentUser::context).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void missingAuthenticationIsDenied() {
        assertThatThrownBy(currentUser::context).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void labellingWithAGroupYouAreNotInIsDenied() {
        authenticate("bob", "GROUP_public", "GROUP_eng");

        assertThat(currentUser.requireOwnGroups(List.of("public", "eng"))).containsExactly("public", "eng");
        assertThat(currentUser.requireOwnGroups(null)).isNull();      // null = server default label
        assertThat(currentUser.requireOwnGroups(List.of())).isEmpty();

        assertThatThrownBy(() -> currentUser.requireOwnGroups(List.of("public", "hr")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("hr");
    }
}
