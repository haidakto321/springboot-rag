package com.example.springbootrag.service;

import com.example.springbootrag.chunk.Chunk;
import com.example.springbootrag.chunk.MarkdownChunker;
import com.example.springbootrag.chunk.WordWindowChunker;
import com.example.springbootrag.config.GraphProperties;
import com.example.springbootrag.config.GuardProperties;
import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.graph.EntityExtractor;
import com.example.springbootrag.guard.QuarantineRequiredException;
import com.example.springbootrag.guard.SecretScanner;
import com.example.springbootrag.graph.ExtractedGraph;
import com.example.springbootrag.graph.WikiLinkParser;
import com.example.springbootrag.repository.DocEdgeRepository;
import com.example.springbootrag.repository.DocumentRegistry;
import com.example.springbootrag.repository.EntityRepository;
import com.example.springbootrag.repository.PgVectorRepository;
import com.example.springbootrag.repository.QdrantRepository;
import com.example.springbootrag.security.SecurityProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

@Service
public class IngestService {

    private final EmbeddingProvider embeddings;
    private final PgVectorRepository pgVector;
    private final QdrantRepository qdrant;
    private final ProjectService projectService;
    private final DocEdgeRepository docEdges;
    private final EntityExtractor entityExtractor;
    private final EntityRepository entityRepo;
    private final GraphProperties graphProps;
    private final SecurityProperties securityProps;
    private final DocumentRegistry documentRegistry;
    private final GuardProperties guard;
    // Hard ceiling per chunk so an atomic table/code block can never exceed the embedding
    // model's context window (nomic-embed-text runs at ~2048 tokens under Ollama). Dense
    // tables (IDs, numbers, pipes) tokenize near 1 char/token, so 2000 chars stays under
    // the 2048-token limit even in the worst case.
    private static final int MAX_CHUNK_CHARS = 2000;
    private final WordWindowChunker wordWindow = new WordWindowChunker(120, 20);
    private final MarkdownChunker markdown = new MarkdownChunker(300, new WordWindowChunker(120, 20));
    private final WikiLinkParser linkParser = new WikiLinkParser();

    public IngestService(EmbeddingProvider embeddings,
                         PgVectorRepository pgVector,
                         QdrantRepository qdrant,
                         ProjectService projectService,
                         DocEdgeRepository docEdges,
                         EntityExtractor entityExtractor,
                         EntityRepository entityRepo,
                         GraphProperties graphProps,
                         SecurityProperties securityProps,
                         DocumentRegistry documentRegistry,
                         GuardProperties guard) {
        this.embeddings = embeddings;
        this.pgVector = pgVector;
        this.qdrant = qdrant;
        this.projectService = projectService;
        this.docEdges = docEdges;
        this.entityExtractor = entityExtractor;
        this.entityRepo = entityRepo;
        this.graphProps = graphProps;
        this.securityProps = securityProps;
        this.documentRegistry = documentRegistry;
        this.guard = guard;
    }

    // ---- Legacy wrappers (resolve default project) ----------------------------------------

    /** Raw-text ingest via the default project, labelled with the default group. */
    public int ingest(String docId, String text) {
        return ingest(projectService.defaultProjectId(), docId, text);
    }

    /** Markdown ingest via the default project, labelled with the default group. */
    public int ingestMarkdown(String docId, String sourceFile, String markdownText) {
        return ingestMarkdown(projectService.defaultProjectId(), docId, sourceFile, markdownText);
    }

    /** Delete via the default project. */
    public void delete(String docId) {
        delete(projectService.defaultProjectId(), docId);
    }

    // ---- Project-scoped methods -----------------------------------------------------------

    /** Raw-text ingest: word-window chunking, no metadata. */
    public int ingest(long projectId, String docId, String text) {
        return ingestChunks(projectId, docId, null, wordWindow.chunk(text), null, null);
    }

    /** Markdown file ingest: structure-aware chunking with heading breadcrumbs. */
    public int ingestMarkdown(long projectId, String docId, String sourceFile, String markdownText) {
        return ingestMarkdown(projectId, docId, sourceFile, markdownText, null, null);
    }

    /** Markdown file ingest with an explicit document updated_at (e.g. git commit date). */
    public int ingestMarkdown(long projectId, String docId, String sourceFile,
                              String markdownText, Instant updatedAt) {
        return ingestMarkdown(projectId, docId, sourceFile, markdownText, updatedAt, null);
    }

    /**
     * Markdown file ingest with an explicit updated_at and access label.
     * A null or empty {@code allowedGroups} falls back to the configured default group - never to
     * "no label", which would make the document unreadable by everyone.
     */
    public int ingestMarkdown(long projectId, String docId, String sourceFile,
                              String markdownText, Instant updatedAt, List<String> allowedGroups) {
        return ingestMarkdown(projectId, docId, sourceFile, markdownText, updatedAt, allowedGroups,
                true);
    }

    /** {@code scanForSecrets} is false only for a release from quarantine - see the funnel below. */
    public int ingestMarkdown(long projectId, String docId, String sourceFile,
                              String markdownText, Instant updatedAt, List<String> allowedGroups,
                              boolean scanForSecrets) {
        int stored = ingestChunks(projectId, docId, sourceFile, markdown.chunk(markdownText),
                updatedAt, allowedGroups, null, null, scanForSecrets);
        // Structural edges: one 'link' edge per outbound cross-page reference.
        for (String dst : linkParser.outboundDocIds(markdownText)) {
            docEdges.insertLink(projectId, docId, dst);
        }
        return stored;
    }

    /**
     * Upsert-by-project+doc: clear any existing chunks for this project/docId first so
     * re-ingesting the same document replaces it instead of accumulating duplicates.
     */
    public int ingestChunks(long projectId, String docId, String sourceFile, List<Chunk> chunks) {
        return ingestChunks(projectId, docId, sourceFile, chunks, null, null);
    }

    /**
     * Upsert-by-project+doc: clear any existing chunks for this project/docId first so
     * re-ingesting the same document replaces it instead of accumulating duplicates.
     *
     * <p>Every chunk of a document inherits the same access label, resolved once here. Group names
     * are validated against the configured directory so a typo is a 400 rather than a document
     * that silently nobody can read.
     */
    public int ingestChunks(long projectId, String docId, String sourceFile, List<Chunk> chunks,
                            Instant updatedAt, List<String> allowedGroups) {
        return ingestChunks(projectId, docId, sourceFile, chunks, updatedAt, allowedGroups, null, null);
    }

    /**
     * Record-aware ingest. {@code perChunkMetadataJson}, when non-null, must be the same size as
     * {@code chunks} - a list that no longer lines up would attach one field group's provenance to
     * another's text, which is silent and unfindable, so it is a loud failure instead.
     *
     * <p>Callers that pass metadata must run {@link #capToBudget} themselves first: capping can
     * split one block into several, and the metadata list has to be built against the capped list.
     */
    public int ingestChunks(long projectId, String docId, String sourceFile, List<Chunk> chunks,
                            Instant updatedAt, List<String> allowedGroups,
                            String docType, List<String> perChunkMetadataJson) {
        return ingestChunks(projectId, docId, sourceFile, chunks, updatedAt, allowedGroups,
                docType, perChunkMetadataJson, true);
    }

    /**
     * The single funnel every ingest path crosses, and therefore where the credential scan belongs.
     *
     * <p>{@code scanForSecrets} is false only for a release from quarantine: re-running the rule
     * that held the document would refuse the exact document a human just decided to accept.
     *
     * @throws com.example.springbootrag.guard.QuarantineRequiredException when the text carries a
     *         credential. Callers that hold the document's original form catch it and store it;
     *         callers that do not, fail loudly rather than indexing a secret.
     */
    public int ingestChunks(long projectId, String docId, String sourceFile, List<Chunk> chunks,
                            Instant updatedAt, List<String> allowedGroups,
                            String docType, List<String> perChunkMetadataJson,
                            boolean scanForSecrets) {
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("docId is required");
        }
        if (scanForSecrets && guard.getQuarantine().isEnabled()) {
            List<SecretScanner.Finding> findings = SecretScanner.scan(joinedText(chunks));
            if (!findings.isEmpty()) {
                throw new QuarantineRequiredException(docId, findings);
            }
        }
        List<String> groups = resolveGroups(allowedGroups);
        chunks = capToBudget(chunks);
        if (perChunkMetadataJson != null && perChunkMetadataJson.size() != chunks.size()) {
            throw new IllegalStateException("metadata list size (" + perChunkMetadataJson.size()
                    + ") does not match chunk count (" + chunks.size() + ")");
        }
        delete(projectId, docId);
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            String meta = perChunkMetadataJson == null ? null : perChunkMetadataJson.get(i);
            float[] vec = embeddings.embed(chunk.text());
            long id = pgVector.insert(projectId, docId, chunk.position(), chunk.text(),
                    sourceFile, chunk.headingPath(), vec, updatedAt, groups, docType, meta);
            try {
                qdrant.upsert(id, projectId, docId, chunk.position(), chunk.text(),
                        sourceFile, chunk.headingPath(), vec, groups, docType, meta);
            } catch (ExecutionException | InterruptedException e) {
                throw new IllegalStateException("Qdrant upsert failed", e);
            }
            if (semanticEnabled()) {
                extractAndPersist(projectId, id, chunk.text());
            }
        }
        return chunks.size();
    }

    /** Everything that is about to be embedded, as one string for the scanner. */
    private static String joinedText(List<Chunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (Chunk c : chunks) {
            sb.append(c.text()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Null/empty means "the default group". Unknown names are rejected: an access label pointing
     * at a group nobody belongs to produces a document that is silently invisible, which is far
     * harder to notice than an error at upload time.
     */
    private List<String> resolveGroups(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of(securityProps.getDefaultGroup());
        }
        Set<String> known = securityProps.knownGroups();
        List<String> cleaned = new java.util.ArrayList<>();
        for (String g : requested) {
            if (g == null || g.isBlank()) continue;
            String name = g.strip();
            if (!known.contains(name)) {
                throw new IllegalArgumentException("unknown group '" + name + "' - known groups: " + known);
            }
            if (!cleaned.contains(name)) cleaned.add(name);
        }
        if (cleaned.isEmpty()) {
            return List.of(securityProps.getDefaultGroup());
        }
        return cleaned;
    }

    /**
     * Removes a document from every store that holds part of it.
     *
     * <p>Qdrant goes FIRST on purpose: it is the fallible store, so if it fails the Postgres rows
     * survive and the delete can be retried. The other order loses the rows and orphans the
     * vectors forever - see LEARNINGS section 13, which stated the rule while the code did the
     * opposite.
     *
     * <p>Inbound edges go too. Feedback labels and traces deliberately do not: labels are eval
     * evidence keyed by (doc_id, chunk_index) and a record can come back on the next sync, and a
     * trace is a record of what was actually answered.
     */
    public void delete(long projectId, String docId) {
        try {
            qdrant.deleteByDocId(projectId, docId);
        } catch (ExecutionException | InterruptedException e) {
            throw new IllegalStateException("Qdrant delete failed", e);
        }
        pgVector.deleteByDocId(projectId, docId);
        docEdges.deleteBySrcDoc(projectId, docId);
        docEdges.deleteByDstDoc(projectId, docId);
        documentRegistry.delete(projectId, docId);
        entityRepo.gcOrphanEntities(projectId);
    }

    /**
     * Safety net: no chunk may exceed the embedding model's context window. The MarkdownChunker
     * keeps code blocks and pipe tables atomic (may be huge); a single over-budget chunk makes
     * the embedding call fail ("input length exceeds the context length"). Any chunk over
     * {@code MAX_CHUNK_CHARS} is split at whitespace (hard-cut for a single giant token) and the
     * whole list is renumbered so chunk indexes stay contiguous.
     */
    public static List<Chunk> capToBudget(List<Chunk> chunks) {
        boolean anyOver = false;
        for (Chunk c : chunks) {
            if (c.text().length() > MAX_CHUNK_CHARS) { anyOver = true; break; }
        }
        if (!anyOver) return chunks;
        List<Chunk> out = new java.util.ArrayList<>();
        int pos = 0;
        for (Chunk c : chunks) {
            if (c.text().length() <= MAX_CHUNK_CHARS) {
                out.add(new Chunk(c.text(), c.headingPath(), pos++));
            } else {
                for (String piece : splitToBudget(c.text(), MAX_CHUNK_CHARS)) {
                    out.add(new Chunk(piece, c.headingPath(), pos++));
                }
            }
        }
        return out;
    }

    /** Splits text into <= max-char pieces, preferring a newline/space boundary near the cap. */
    private static List<String> splitToBudget(String text, int max) {
        List<String> pieces = new java.util.ArrayList<>();
        int i = 0, n = text.length();
        while (i < n) {
            int end = Math.min(i + max, n);
            if (end < n) {
                int nl = text.lastIndexOf('\n', end);
                int sp = (nl > i) ? nl : text.lastIndexOf(' ', end);
                if (sp > i) end = sp;   // break on whitespace; else hard-cut a giant token
            }
            pieces.add(text.substring(i, end));
            i = end;
            while (i < n && Character.isWhitespace(text.charAt(i))) i++;  // skip boundary whitespace
        }
        return pieces;
    }

    private boolean semanticEnabled() {
        String e = graphProps.getEdges();
        return "semantic".equalsIgnoreCase(e) || "both".equalsIgnoreCase(e);
    }

    /** Best-effort: EntityExtractor swallows model/parse failures internally and returns an empty graph. */
    private void extractAndPersist(long projectId, long chunkId, String text) {
        ExtractedGraph g = entityExtractor.extract(text);
        Map<String, Long> ids = new HashMap<>();
        for (ExtractedGraph.Entity e : g.entities()) {
            long eid = entityRepo.upsertEntity(projectId, e.name(), e.type());
            entityRepo.linkChunk(chunkId, eid);
            ids.put(e.name().trim().toLowerCase(), eid);
        }
        for (ExtractedGraph.Relation r : g.relations()) {
            Long s = ids.get(r.src().trim().toLowerCase());
            Long d = ids.get(r.dst().trim().toLowerCase());
            if (s != null && d != null) {
                entityRepo.insertEdge(projectId, s, d, r.rel());
            }
        }
    }
}
