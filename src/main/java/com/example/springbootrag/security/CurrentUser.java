package com.example.springbootrag.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the {@link SearchContext} from the authenticated principal.
 *
 * <p>This is the ONLY place a SearchContext is created for an HTTP request. Controllers must not
 * accept a principal, a group, or anything equivalent as a parameter: whatever the browser sends
 * is a suggestion, not a permission.
 */
@Component
public class CurrentUser {

    /** Groups are carried as authorities so Spring Security owns them end to end. */
    public static final String GROUP_PREFIX = "GROUP_";

    /**
     * Write-side counterpart of the read filter: a caller may only label a document with groups
     * they belong to. Without this, anyone could stamp a document 'hr' - planting content inside a
     * group they cannot read, or hiding their own upload from themselves. Null/empty means "use
     * the server default" and is allowed.
     *
     * @return the same list, for use inline at the call site
     */
    public List<String> requireOwnGroups(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return requested;
        }
        Set<String> mine = context().groups();
        for (String g : requested) {
            if (g == null || g.isBlank()) continue;
            if (!mine.contains(g.strip())) {
                throw new AccessDeniedException("not a member of group '" + g.strip() + "'");
            }
        }
        return requested;
    }

    /**
     * The caller's name, or null when there is no authenticated one.
     *
     * <p>For audit writes only, never for authorisation - "who did this, as best we know" is a
     * different question from "may they". Some write paths legitimately run without a security
     * context: a bulk import's async thread, a tool invoked directly. Recording null there is
     * honest, and better than turning a successful containment into an authentication error.
     */
    public String principalOrNull() {
        try {
            return context().principal();
        } catch (AccessDeniedException e) {
            return null;
        }
    }

    public SearchContext context() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            throw new AccessDeniedException("not authenticated");
        }
        Set<String> groups = new LinkedHashSet<>();
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String value = authority.getAuthority();
            if (value != null && value.startsWith(GROUP_PREFIX)) {
                groups.add(value.substring(GROUP_PREFIX.length()));
            }
        }
        return SearchContext.of(auth.getName(), groups);
    }
}
