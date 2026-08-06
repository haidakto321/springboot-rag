package com.example.springbootrag.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Storage for optional per-(project, docType) render profiles. */
@Repository
public class ProfileRepository {

    /** The stored profile body plus the version that participates in the freshness hash. */
    public record StoredProfile(String docType, String body, int version) {}

    private final JdbcTemplate jdbc;

    public ProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Upsert; returns the version after the write, so an edit is detectable downstream. */
    public int upsert(long projectId, String docType, String body) {
        Integer version = jdbc.queryForObject(
                "INSERT INTO render_profile (project_id, doc_type, body) VALUES (?, ?, ?::jsonb) " +
                        "ON CONFLICT (project_id, doc_type) DO UPDATE " +
                        "SET body = EXCLUDED.body, version = render_profile.version + 1, updated_at = now() " +
                        "RETURNING version",
                Integer.class, projectId, docType, body);
        return version == null ? 1 : version;
    }

    public Optional<StoredProfile> find(long projectId, String docType) {
        return jdbc.query(
                "SELECT doc_type, body::text AS body, version FROM render_profile " +
                        "WHERE project_id = ? AND doc_type = ?",
                (rs, n) -> new StoredProfile(rs.getString("doc_type"), rs.getString("body"),
                        rs.getInt("version")),
                projectId, docType).stream().findFirst();
    }

    public List<StoredProfile> list(long projectId) {
        return jdbc.query(
                "SELECT doc_type, body::text AS body, version FROM render_profile " +
                        "WHERE project_id = ? ORDER BY doc_type",
                (rs, n) -> new StoredProfile(rs.getString("doc_type"), rs.getString("body"),
                        rs.getInt("version")),
                projectId);
    }
}
