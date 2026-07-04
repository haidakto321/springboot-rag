package com.example.springbootrag.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Repository
public class EntityRepository {

    private final JdbcTemplate jdbc;

    public EntityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long upsertEntity(long projectId, String nameDisplay, String type) {
        String norm = nameDisplay.trim().toLowerCase();
        return jdbc.queryForObject(
                "INSERT INTO entity (project_id, name_norm, name_display, type, mention_count) " +
                "VALUES (?, ?, ?, ?, 1) " +
                "ON CONFLICT (project_id, name_norm) DO UPDATE SET mention_count = entity.mention_count + 1 " +
                "RETURNING id",
                Long.class, projectId, norm, nameDisplay.trim(), type);
    }

    public void linkChunk(long chunkId, long entityId) {
        jdbc.update("INSERT INTO chunk_entity (chunk_id, entity_id) VALUES (?, ?) " +
                "ON CONFLICT DO NOTHING", chunkId, entityId);
    }

    public void insertEdge(long projectId, long srcEntity, long dstEntity, String relation) {
        jdbc.update(
                "INSERT INTO entity_edge (project_id, src_entity, dst_entity, relation) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (project_id, src_entity, dst_entity, relation) DO NOTHING",
                projectId, srcEntity, dstEntity, relation);
    }

    public List<Long> matchEntityIds(long projectId, List<String> names, int minMentions) {
        if (names == null || names.isEmpty()) return List.of();
        List<String> norm = names.stream().map(s -> s.trim().toLowerCase()).toList();
        String ph = String.join(",", Collections.nCopies(norm.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        args.addAll(norm);
        args.add(minMentions);
        return jdbc.queryForList(
                "SELECT id FROM entity WHERE project_id = ? AND name_norm IN (" + ph + ") " +
                "AND mention_count >= ?",
                Long.class, args.toArray());
    }

    public List<Long> neighborEntityIds(long projectId, List<Long> entityIds) {
        if (entityIds == null || entityIds.isEmpty()) return List.of();
        String ph = String.join(",", Collections.nCopies(entityIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        args.addAll(entityIds);
        return jdbc.queryForList(
                "SELECT DISTINCT dst_entity FROM entity_edge " +
                "WHERE project_id = ? AND src_entity IN (" + ph + ")",
                Long.class, args.toArray());
    }

    public List<Long> chunkIdsForEntities(List<Long> entityIds) {
        if (entityIds == null || entityIds.isEmpty()) return List.of();
        String ph = String.join(",", Collections.nCopies(entityIds.size(), "?"));
        return jdbc.queryForList(
                "SELECT DISTINCT chunk_id FROM chunk_entity WHERE entity_id IN (" + ph + ")",
                Long.class, entityIds.toArray());
    }

    public void gcOrphanEntities(long projectId) {
        jdbc.update(
                "DELETE FROM entity WHERE project_id = ? AND id NOT IN " +
                "(SELECT DISTINCT entity_id FROM chunk_entity)",
                projectId);
    }
}
