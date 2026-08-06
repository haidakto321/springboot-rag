package com.example.springbootrag.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class DocEdgeRepository {

    private final JdbcTemplate jdbc;

    public DocEdgeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertLink(long projectId, String srcDoc, String dstDoc) {
        upsert(projectId, srcDoc, dstDoc, "link");
    }

    public void insertHierarchy(long projectId, String parentDoc, String childDoc) {
        upsert(projectId, parentDoc, childDoc, "hierarchy");
    }

    private void upsert(long projectId, String srcDoc, String dstDoc, String kind) {
        jdbc.update(
                "INSERT INTO doc_edge (project_id, src_doc, dst_doc, kind) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (project_id, src_doc, dst_doc, kind) DO NOTHING",
                projectId, srcDoc, dstDoc, kind);
    }

    /** Distinct dst_doc reachable in one hop from any of srcDocs (both kinds). */
    public List<String> neighbors(long projectId, List<String> srcDocs) {
        if (srcDocs == null || srcDocs.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(srcDocs.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        args.addAll(srcDocs);
        return jdbc.queryForList(
                "SELECT DISTINCT dst_doc FROM doc_edge " +
                "WHERE project_id = ? AND src_doc IN (" + placeholders + ")",
                String.class, args.toArray());
    }

    public void deleteBySrcDoc(long projectId, String srcDoc) {
        jdbc.update("DELETE FROM doc_edge WHERE project_id = ? AND src_doc = ?", projectId, srcDoc);
    }

    /**
     * Removes edges POINTING AT this doc. Without it a deleted document stays reachable by graph
     * expansion: the hop succeeds, the chunk load returns nothing, and the neighbour is a ghost.
     */
    public void deleteByDstDoc(long projectId, String dstDoc) {
        jdbc.update("DELETE FROM doc_edge WHERE project_id = ? AND dst_doc = ?", projectId, dstDoc);
    }
}
