package com.example.springbootrag.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Roles are a separate rail from groups: a group says what you may read, a role says what you may
 * do. Both arrive as authorities so Spring Security owns them end to end.
 */
class SecurityConfigRolesTest {

    private static SecurityProperties.User user(String name, List<String> groups, List<String> roles) {
        SecurityProperties.User u = new SecurityProperties.User();
        u.setUsername(name);
        u.setPassword("pw");
        u.setGroups(groups);
        u.setRoles(roles);
        return u;
    }

    private static UserDetailsService directory(SecurityProperties.User... users) {
        SecurityProperties props = new SecurityProperties();
        props.setUsers(List.of(users));
        return new SecurityConfig().userDetailsService(props);
    }

    private static List<String> authorities(UserDetails details) {
        return details.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void aConfiguredRoleBecomesARoleAuthorityAlongsideTheGroups() {
        UserDetails alice = directory(
                user("alice", List.of("public", "hr"), List.of(Roles.QUARANTINE_RELEASE)))
                .loadUserByUsername("alice");

        assertThat(authorities(alice)).containsExactlyInAnyOrder(
                "GROUP_public", "GROUP_hr", Roles.PREFIX + Roles.QUARANTINE_RELEASE);
    }

    @Test
    void aUserWithNoRolesGetsNoRoleAuthority() {
        UserDetails haiks = directory(user("haiks", List.of("public"), List.of()))
                .loadUserByUsername("haiks");

        assertThat(authorities(haiks)).containsExactly("GROUP_public");
    }

    @Test
    void rolesAreNotGroupsAndDoNotLeakIntoKnownGroups() {
        // knownGroups() is what ingest validates an access label against. A role appearing there
        // would let a caller label a document 'quarantine-release' and have it accepted.
        SecurityProperties props = new SecurityProperties();
        props.setUsers(List.of(user("alice", List.of("public", "hr"),
                List.of(Roles.QUARANTINE_RELEASE))));

        assertThat(props.knownGroups()).containsExactlyInAnyOrder("public", "hr");
    }
}
