package com.example.springbootrag.service;

import com.example.springbootrag.chunk.Chunk;
import com.example.springbootrag.chunk.MarkdownChunker;
import com.example.springbootrag.chunk.WordWindowChunker;
import com.example.springbootrag.config.GraphProperties;
import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.graph.EntityExtractor;
import com.example.springbootrag.graph.ExtractedGraph;
import com.example.springbootrag.graph.WikiLinkParser;
import com.example.springbootrag.repository.DocEdgeRepository;
import com.example.springbootrag.repository.EntityRepository;
import com.example.springbootrag.repository.PgVectorRepository;
import com.example.springbootrag.repository.QdrantRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
                         GraphProperties graphProps) {
        this.embeddings = embeddings;
        this.pgVector = pgVector;
        this.qdrant = qdrant;
        this.projectService = projectService;
        this.docEdges = docEdges;
        this.entityExtractor = entityExtractor;
        this.entityRepo = entityRepo;
        this.graphProps = graphProps;
    }

    // ---- Legacy wrappers (resolve default project) ----------------------------------------

    /** Raw-text ingest via the default project. */
    public int ingest(String docId, String text) {
        return ingest(projectService.defaultProjectId(), docId, text);
    }

    /** Markdown ingest via the default project. */
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
        return ingestChunks(projectId, docId, null, wordWindow.chunk(text), null);
    }

    /** Markdown file ingest: structure-aware chunking with heading breadcrumbs. */
    public int ingestMarkdown(long projectId, String docId, String sourceFile, String markdownText) {
        return ingestMarkdown(projectId, docId, sourceFile, markdownText, null);
    }

    /** Markdown file ingest with an explicit document updated_at (e.g. git commit date). */
    public int ingestMarkdown(long projectId, String docId, String sourceFile,
                              String markdownText, Instant updatedAt) {
        int stored = ingestChunks(projectId, docId, sourceFile, markdown.chunk(markdownText), updatedAt);
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
        return ingestChunks(projectId, docId, sourceFile, chunks, null);
    }

    /**
     * Upsert-by-project+doc: clear any existing chunks for this project/docId first so
     * re-ingesting the same document replaces it instead of accumulating duplicates.
     */
    public int ingestChunks(long projectId, String docId, String sourceFile, List<Chunk> chunks, Instant updatedAt) {
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("docId is required");
        }
        delete(projectId, docId);
        for (Chunk chunk : chunks) {
            float[] vec = embeddings.embed(chunk.text());
            long id = pgVector.insert(projectId, docId, chunk.position(), chunk.text(),
                    sourceFile, chunk.headingPath(), vec, updatedAt);
            try {
                qdrant.upsert(id, projectId, docId, chunk.position(), chunk.text(),
                        sourceFile, chunk.headingPath(), vec);
            } catch (ExecutionException | InterruptedException e) {
                throw new IllegalStateException("Qdrant upsert failed", e);
            }
            if (semanticEnabled()) {
                extractAndPersist(projectId, id, chunk.text());
            }
        }
        return chunks.size();
    }

    public void delete(long projectId, String docId) {
        pgVector.deleteByDocId(projectId, docId);
        try {
            qdrant.deleteByDocId(projectId, docId);
        } catch (ExecutionException | InterruptedException e) {
            throw new IllegalStateException("Qdrant delete failed", e);
        }
        docEdges.deleteBySrcDoc(projectId, docId);
        entityRepo.gcOrphanEntities(projectId);
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
