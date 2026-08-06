package com.example.springbootrag.service;

import com.example.springbootrag.chunk.Chunk;
import com.example.springbootrag.config.EmbeddingProperties;
import com.example.springbootrag.guard.InjectionScanner;
import com.example.springbootrag.record.RecordHash;
import com.example.springbootrag.record.RecordRenderer;
import com.example.springbootrag.record.RenderProfile;
import com.example.springbootrag.record.RenderedBlock;
import com.example.springbootrag.repository.DocumentRegistry;
import com.example.springbootrag.repository.PgVectorRepository;
import com.example.springbootrag.repository.ProfileRepository;
import com.example.springbootrag.repository.QdrantRepository;
import com.example.springbootrag.web.dto.RecordRequest;
import com.example.springbootrag.web.dto.RecordResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Ingests one extracted record: render, hash, decide, store.
 *
 * <p>Two hashes drive three outcomes. Rendered text and settings unchanged -> skipped. Rendered
 * text unchanged but the raw record changed (a confidence jitter, a new bbox) -> metadata
 * refreshed in place, no embedding call. Anything else -> full re-index.
 */
@Service
public class RecordIngestService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RecordRenderer renderer = new RecordRenderer();
    private final IngestService ingest;
    private final DocumentRegistry registry;
    private final ProfileRepository profiles;
    private final PgVectorRepository pgVector;
    private final QdrantRepository qdrant;
    private final EmbeddingProperties embeddingProps;

    public RecordIngestService(IngestService ingest, DocumentRegistry registry,
                               ProfileRepository profiles, PgVectorRepository pgVector,
                               QdrantRepository qdrant, EmbeddingProperties embeddingProps) {
        this.ingest = ingest;
        this.registry = registry;
        this.profiles = profiles;
        this.pgVector = pgVector;
        this.qdrant = qdrant;
        this.embeddingProps = embeddingProps;
    }

    public RecordResponse ingest(long projectId, RecordRequest req) {
        validate(req);

        Optional<ProfileRepository.StoredProfile> stored = profiles.find(projectId, req.docType());
        RenderProfile profile = stored.map(p -> RenderProfile.parse(p.body())).orElse(null);
        Integer profileVersion = stored.map(ProfileRepository.StoredProfile::version).orElse(null);

        List<RenderedBlock> blocks = renderer.render(req.record(), profile);
        if (blocks.isEmpty()) {
            // Storing nothing silently is the failure that gets discovered a month later.
            throw new IllegalArgumentException(
                    "record rendered to no text - every field was empty, excluded, or filter-only");
        }

        String contentHash = RecordHash.ofBlocks(blocks);
        String rawHash = RecordHash.ofJson(req.record());
        List<String> groups = req.groups() == null ? List.of() : req.groups();
        boolean force = Boolean.TRUE.equals(req.force());

        Optional<DocumentRegistry.Entry> existing = registry.find(projectId, req.docId());
        if (!force && existing.isPresent()) {
            DocumentRegistry.Entry e = existing.get();
            boolean sameText = e.contentHash().equals(contentHash)
                    && e.embedModel().equals(embeddingProps.getModel())
                    && Objects.equals(e.profileVersion(), profileVersion)
                    && sameGroups(e.allowedGroups(), groups);
            if (sameText && e.rawHash().equals(rawHash)) {
                return new RecordResponse(req.docId(), e.chunkCount(), "skipped", List.of());
            }
            if (sameText) {
                refreshMetadata(projectId, req, blocks);
                registry.upsert(projectId, new DocumentRegistry.Entry(
                        req.docId(), req.docType(), "record", contentHash, rawHash,
                        e.embedModel(), profileVersion, e.allowedGroups(), e.chunkCount()));
                return new RecordResponse(req.docId(), e.chunkCount(), "metadata-refreshed", List.of());
            }
        }

        // Cap BEFORE building metadata: capping can split a block and renumbers the whole list,
        // so a metadata list built against the uncapped blocks would drift out of alignment.
        List<Chunk> capped = IngestService.capToBudget(toChunks(blocks));
        List<String> metadata = metadataFor(capped, blocks, req);

        int storedCount = ingest.ingestChunks(projectId, req.docId(), sourceFileOf(req), capped,
                null, groups, req.docType(), metadata);

        registry.upsert(projectId, new DocumentRegistry.Entry(
                req.docId(), req.docType(), "record", contentHash, rawHash,
                embeddingProps.getModel(), profileVersion,
                groups.isEmpty() ? List.of("public") : groups, storedCount));

        return new RecordResponse(req.docId(), storedCount, "indexed",
                InjectionScanner.scan(joined(blocks)));
    }

    /* ---- helpers ---- */

    private static void validate(RecordRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (req.docId() == null || req.docId().isBlank()) {
            throw new IllegalArgumentException("docId is required");
        }
        if (req.docType() == null || req.docType().isBlank()) {
            throw new IllegalArgumentException("docType is required");
        }
        if (req.record() == null || !req.record().isObject()) {
            throw new IllegalArgumentException("record must be a JSON object");
        }
    }

    private static List<Chunk> toChunks(List<RenderedBlock> blocks) {
        List<Chunk> chunks = new ArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            chunks.add(new Chunk(blocks.get(i).text(), blocks.get(i).breadcrumb(), i));
        }
        return chunks;
    }

    /**
     * One metadata JSON per capped chunk: the values and provenance of the block it came from, the
     * caller's own metadata, and the chunk-level confidence aggregate. Pieces of a split block
     * share their parent's metadata - each piece is still the same field group.
     */
    private List<String> metadataFor(List<Chunk> capped, List<RenderedBlock> blocks,
                                     RecordRequest req) {
        Map<String, RenderedBlock> byBreadcrumb = new LinkedHashMap<>();
        for (RenderedBlock b : blocks) byBreadcrumb.put(b.breadcrumb(), b);

        List<String> out = new ArrayList<>(capped.size());
        for (Chunk c : capped) {
            RenderedBlock b = byBreadcrumb.get(c.headingPath());
            Map<String, Object> values = new LinkedHashMap<>(b == null ? Map.of() : b.values());
            if (req.metadata() != null) values.putAll(req.metadata());

            Map<String, Object> node = new LinkedHashMap<>();
            node.put("values", values);
            node.put("prov", b == null ? Map.of() : b.prov());
            node.put("conf", confidenceAggregate(b));
            try {
                out.add(MAPPER.writeValueAsString(node));
            } catch (Exception e) {
                throw new IllegalStateException("could not serialise chunk metadata", e);
            }
        }
        return out;
    }

    /**
     * min/avg over NUMERIC confidences only. Absent when nothing reported one - a default of 0
     * would hide the chunk from every threshold filter and a default of 1.0 would be a fabricated
     * guarantee.
     */
    private Map<String, Object> confidenceAggregate(RenderedBlock b) {
        if (b == null) return Map.of();
        List<Double> scores = new ArrayList<>();
        collectConfidences(b.prov(), scores);
        if (scores.isEmpty()) return Map.of();
        double min = scores.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        double avg = scores.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        return Map.of("min", min, "avg", avg);
    }

    /** Provenance is nested by field path, so the scan has to walk the whole tree. */
    private static void collectConfidences(Map<?, ?> node, List<Double> out) {
        for (Map.Entry<?, ?> e : node.entrySet()) {
            Object v = e.getValue();
            if ("confidence".equals(e.getKey()) && v instanceof Number n) {
                out.add(n.doubleValue());
            } else if (v instanceof Map<?, ?> m) {
                collectConfidences(m, out);
            }
        }
    }

    /** Rewrites chunk metadata in both stores without touching a single vector. */
    private void refreshMetadata(long projectId, RecordRequest req, List<RenderedBlock> blocks) {
        List<Chunk> capped = IngestService.capToBudget(toChunks(blocks));
        List<String> metadata = metadataFor(capped, blocks, req);
        for (int i = 0; i < capped.size(); i++) {
            pgVector.updateMetadata(projectId, req.docId(), i, metadata.get(i));
            try {
                qdrant.updateMetadata(projectId, req.docId(), i, metadata.get(i));
            } catch (Exception e) {
                throw new IllegalStateException("Qdrant metadata refresh failed", e);
            }
        }
    }

    /** An omitted groups list means "keep whatever is stored", not "change the label to nothing". */
    private static boolean sameGroups(List<String> stored, List<String> requested) {
        if (requested == null || requested.isEmpty()) return true;
        return new HashSet<>(stored).equals(new HashSet<>(requested));
    }

    private static String sourceFileOf(RecordRequest req) {
        Object sf = req.metadata() == null ? null : req.metadata().get("sourceFile");
        return sf == null ? null : sf.toString();
    }

    private static String joined(List<RenderedBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (RenderedBlock b : blocks) sb.append(b.text()).append('\n');
        return sb.toString();
    }
}
