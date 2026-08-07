package com.example.springbootrag.understand;

import com.example.springbootrag.config.UnderstandProperties;
import com.example.springbootrag.repository.FacetRepository;
import com.example.springbootrag.security.SearchContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The facet list handed to the extractor, cached because it runs once per question while the
 * corpus changes far more slowly than it is queried.
 */
@Service
public class FacetCatalogue {

    private static final Logger log = LoggerFactory.getLogger(FacetCatalogue.class);

    /** Registers the properties without another @EnableConfigurationProperties elsewhere. */
    @Configuration
    @EnableConfigurationProperties(UnderstandProperties.class)
    static class Props {}

    private record Key(String groups, String projects) {}
    private record Entry(List<Facet> facets, Instant loadedAt) {}

    private final FacetRepository repo;
    private final UnderstandProperties props;
    private final Map<Key, Entry> cache = new ConcurrentHashMap<>();

    public FacetCatalogue(FacetRepository repo, UnderstandProperties props) {
        this.repo = repo;
        this.props = props;
    }

    /**
     * The cache key includes the caller's groups, so the catalogue can never leak the existence of
     * a facet belonging to documents the caller cannot read.
     */
    public List<Facet> forProjects(SearchContext ctx, List<Long> projectIds) {
        Key key = new Key(String.join(",", new java.util.TreeSet<>(ctx.groups())),
                projectIds == null ? "" : projectIds.stream().sorted().map(String::valueOf)
                        .reduce("", (a, b) -> a.isEmpty() ? b : a + "," + b));
        Entry hit = cache.get(key);
        Instant now = Instant.now();
        if (hit != null && Duration.between(hit.loadedAt(), now).getSeconds() < props.getFacetTtlSeconds()) {
            return hit.facets();
        }
        List<Facet> loaded;
        try {
            loaded = typed(repo.facets(ctx, projectIds, props.getFacetSamples()));
        } catch (RuntimeException e) {
            // A broken catalogue must degrade to "no filter", never to a failed answer.
            log.warn("facet catalogue query failed; continuing without facets", e);
            return List.of();
        }
        cache.put(key, new Entry(loaded, now));
        return loaded;
    }

    private static List<Facet> typed(List<Facet> raw) {
        List<Facet> out = new ArrayList<>(raw.size());
        for (Facet f : raw) {
            out.add(new Facet(f.docType(), f.path(), inferType(f.samples()), f.samples(), f.distinctCount()));
        }
        return out;
    }

    /** By value shape across the samples. A mixed column degrades to text, never to a cast error. */
    public static String inferType(List<String> samples) {
        if (samples == null || samples.isEmpty()) return "text";
        boolean allNumbers = true;
        boolean allDates = true;
        for (String s : samples) {
            if (s == null) return "text";
            if (!s.matches("-?\\d+(\\.\\d+)?")) allNumbers = false;
            if (!s.matches("\\d{4}-\\d{2}-\\d{2}")) allDates = false;
        }
        if (allNumbers) return "number";
        if (allDates) return "date";
        return "text";
    }
}
