package com.example.springbootrag.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Sandbox user directory: {@code app.security.users} in application.yml.
 *
 * <p>Passwords are stored in plain text with a {@code {noop}} encoder. That is acceptable ONLY
 * because these are two fake users in a single-developer laboratory whose whole purpose is to
 * demonstrate permission-aware retrieval. A real deployment replaces this whole class with a real
 * identity provider - never copy the noop encoder or the in-memory list.
 */
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    private List<User> users = new ArrayList<>();

    /** Group stamped on ingested chunks when the caller names none. */
    private String defaultGroup = "public";

    /**
     * Stamp the default group on Qdrant points that carry no label at startup, mirroring the
     * one-time UPDATE in schema.sql. Only unlabelled points are touched.
     */
    private boolean backfillQdrantGroups = true;

    public List<User> getUsers() { return users; }
    public void setUsers(List<User> users) { this.users = users; }
    public String getDefaultGroup() { return defaultGroup; }
    public void setDefaultGroup(String defaultGroup) { this.defaultGroup = defaultGroup; }
    public boolean isBackfillQdrantGroups() { return backfillQdrantGroups; }
    public void setBackfillQdrantGroups(boolean backfillQdrantGroups) { this.backfillQdrantGroups = backfillQdrantGroups; }

    /**
     * Every group named by any user, plus the default group. Ingest validates against this so a
     * typo becomes a 400 instead of a document nobody can ever read.
     */
    public Set<String> knownGroups() {
        Set<String> all = new LinkedHashSet<>();
        all.add(defaultGroup);
        for (User u : users) {
            all.addAll(u.getGroups());
        }
        return all;
    }

    public static class User {
        private String username;
        private String password;
        private List<String> groups = new ArrayList<>();
        private List<String> roles = new ArrayList<>();

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public List<String> getGroups() { return groups; }
        public void setGroups(List<String> groups) { this.groups = groups; }

        /**
         * Action permissions - see {@link Roles}. Deliberately NOT part of
         * {@link SecurityProperties#knownGroups()}.
         */
        public List<String> getRoles() { return roles; }
        public void setRoles(List<String> roles) { this.roles = roles; }
    }
}
