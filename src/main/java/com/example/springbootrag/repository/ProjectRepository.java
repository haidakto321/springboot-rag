package com.example.springbootrag.repository;

import com.example.springbootrag.model.Project;
import com.example.springbootrag.web.dto.ProjectSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ProjectRepository {

    private final JdbcTemplate jdbc;

    public ProjectRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long create(String name, String groupName) {
        return jdbc.queryForObject(
            "INSERT INTO projects (name, group_name) VALUES (?, ?) RETURNING id",
            Long.class, name, groupName);
    }

    public Optional<Project> find(long id) {
        return jdbc.query(
            "SELECT id, name, group_name FROM projects WHERE id = ?",
            (rs, n) -> new Project(rs.getLong("id"), rs.getString("name"), rs.getString("group_name")),
            id)
            .stream().findFirst();
    }

    public List<ProjectSummary> listWithCounts() {
        return jdbc.query("""
            SELECT p.id, p.name, p.group_name,
                   count(DISTINCT c.doc_id) AS doc_count,
                   count(c.id)              AS chunk_count
            FROM projects p LEFT JOIN chunks c ON c.project_id = p.id
            GROUP BY p.id, p.name, p.group_name
            ORDER BY p.group_name NULLS LAST, p.name
            """,
            (rs, n) -> new ProjectSummary(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("group_name"),
                rs.getInt("doc_count"),
                rs.getInt("chunk_count")));
    }

    public void rename(long id, String name) {
        jdbc.update("UPDATE projects SET name = ? WHERE id = ?", name, id);
    }

    /** Pass null to clear the group. */
    public void setGroup(long id, String groupName) {
        jdbc.update("UPDATE projects SET group_name = ? WHERE id = ?", groupName, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM projects WHERE id = ?", id);
    }

    public List<String> listGroups() {
        return jdbc.queryForList(
            "SELECT DISTINCT group_name FROM projects WHERE group_name IS NOT NULL ORDER BY group_name",
            String.class);
    }

    /** A null groupName returns an empty list (SQL: group_name = NULL is never true). */
    public List<Long> idsInGroup(String groupName) {
        return jdbc.queryForList(
            "SELECT id FROM projects WHERE group_name = ?",
            Long.class, groupName);
    }
}
