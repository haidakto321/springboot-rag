package com.example.springbootrag.repository;

import com.example.springbootrag.security.SearchContext;
import com.example.springbootrag.understand.Facet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Derives the filterable paths from the metadata that is actually indexed.
 *
 * <p>Derived, never declared: the set of document types is open, so a catalogue built from
 * configuration would be silent about exactly the tenant nobody configured. Read under the
 * caller's access labels like every other read - a facet is data about data, and listing one the
 * caller cannot read is still a leak.
 */
@Repository
public class FacetRepository {

    private final JdbcTemplate jdbc;

    public FacetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Leaf paths under the {@code values} and {@code conf} trees, with sample values and a
     * distinct count. {@code prov} is deliberately excluded: provenance is filterable, but nobody
     * asks a question about a bounding box.
     *
     * <p>Paths come back dotted with array markers already gone ({@code values.lineItems.sku}),
     * which is exactly the shape {@link FilterSql#segments} produces from an API path - so a facet
     * can always be filtered on.
     */
    public List<Facet> facets(SearchContext ctx, List<Long> projectIds, int sampleLimit) {
        if (ctx.readsNothing()) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(sampleLimit, 20));   // interpolated below: keep it sane
        String projectClause = DocFilter.active(projectIds)
                ? " AND c.project_id IN (" + DocFilter.placeholders(projectIds.size()) + ")"
                : "";
        List<Object> args = new ArrayList<>(ctx.groups());
        if (DocFilter.active(projectIds)) args.addAll(projectIds);

        String sql = """
            WITH RECURSIVE seed AS (
                SELECT c.doc_type, r.root || '.' || k.key AS path, k.value AS node
                FROM chunks c
                CROSS JOIN LATERAL (VALUES ('values', c.metadata->'values'),
                                           ('conf',   c.metadata->'conf')) AS r(root, node)
                CROSS JOIN LATERAL jsonb_each(r.node) AS k
                WHERE c.metadata <> '{}'::jsonb AND jsonb_typeof(r.node) = 'object'
                  AND""" + DocFilter.groupClause(ctx.groups()) + projectClause + """

            ), tree AS (
                SELECT doc_type, path, node FROM seed
                UNION ALL
                SELECT t.doc_type, t.path || '.' || k.key, k.value
                FROM tree t
                CROSS JOIN LATERAL jsonb_each(t.node) AS k
                WHERE jsonb_typeof(t.node) = 'object'
            )
            SELECT doc_type, path,
                   count(DISTINCT node #>> '{}') AS distinct_count,
                   (array_agg(DISTINCT node #>> '{}' ORDER BY node #>> '{}'))[1:%d] AS samples
            FROM tree
            WHERE jsonb_typeof(node) <> 'object'
            GROUP BY doc_type, path
            ORDER BY doc_type, path
            """.formatted(limit);

        return jdbc.query(sql, (rs, n) -> new Facet(
                rs.getString("doc_type"),
                rs.getString("path"),
                "text",                        // FacetCatalogue infers the real type from samples
                toList(rs.getArray("samples")),
                rs.getInt("distinct_count")), args.toArray());
    }

    private static List<String> toList(java.sql.Array array) {
        try {
            if (array == null) return List.of();
            return Arrays.stream((Object[]) array.getArray())
                    .filter(java.util.Objects::nonNull).map(Object::toString).toList();
        } catch (java.sql.SQLException e) {
            return List.of();
        }
    }
}
