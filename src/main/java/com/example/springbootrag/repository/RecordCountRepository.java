package com.example.springbootrag.repository;

import com.example.springbootrag.security.SearchContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * How many records match, without reading any of them.
 *
 * <p>The value of this class is that it answers a counting question with a count, instead of with
 * ten retrieved chunks and a model guessing a total from them. It reuses {@link DocFilter} and
 * {@link FilterSql} rather than writing its own predicates: an access-control clause that exists in
 * two places is an access-control clause that will diverge.
 */
@Repository
public class RecordCountRepository {

    private final JdbcTemplate jdbc;

    public RecordCountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Distinct documents the caller may read that match {@code filter}.
     *
     * <p>DISTINCT doc_id because one record renders to several chunks, and "how many invoices" is a
     * question about records. Empty {@code projectIds} means every project the caller may read.
     */
    public long count(SearchContext ctx, List<Long> projectIds, MetadataFilter filter) {
        String projectClause = DocFilter.active(projectIds)
                ? " AND project_id IN (" + DocFilter.placeholders(projectIds.size()) + ")"
                : "";
        FilterSql.Fragment meta = FilterSql.render(filter);
        List<Object> args = new ArrayList<>(ctx.groups());   // order: groups, projects, metadata
        if (DocFilter.active(projectIds)) args.addAll(projectIds);
        args.addAll(meta.args());
        Long n = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT doc_id) FROM chunks WHERE"
                        + DocFilter.groupClause(ctx.groups()) + projectClause + meta.sql(),
                Long.class, args.toArray());
        return n == null ? 0L : n;
    }
}
