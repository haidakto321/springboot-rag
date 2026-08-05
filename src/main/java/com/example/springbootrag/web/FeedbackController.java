package com.example.springbootrag.web;

import com.example.springbootrag.model.FeedbackLabel;
import com.example.springbootrag.repository.FeedbackRepository;
import com.example.springbootrag.service.ProjectService;
import com.example.springbootrag.web.dto.FeedbackRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * Human relevance labels on individual chunks (ROADMAP "Option A", RAG-MASTERY section 3 drill D).
 *
 * <p>Collection only - nothing here is read at query time and no score is ever nudged by a label.
 * The labels exist so offline eval can ask whether the reranker earns its latency on THIS corpus.
 *
 * <p>No auth, matching the rest of this single-user sandbox: any caller can write a label for any
 * project. That is fine for a laboratory and must not be copied into a multi-user system.
 */
@RestController
public class FeedbackController {

    /** Keeps the UNIQUE (project, doc, chunk, query) btree index inside Postgres' row-size limit. */
    static final int MAX_QUERY = 500;
    static final int MAX_LIMIT = 1000;
    static final int DEFAULT_LIMIT = 200;

    private final FeedbackRepository repo;
    private final ProjectService projects;

    public FeedbackController(FeedbackRepository repo, ProjectService projects) {
        this.repo = repo;
        this.projects = projects;
    }

    @PostMapping("/feedback")
    public void record(@RequestBody FeedbackRequest req) {
        String query = requireQuery(req.query());
        long projectId = requireProject(req.projectId());
        String docId = requireDocId(req.docId());
        int chunkIndex = requireChunkIndex(req.chunkIndex());
        repo.upsert(projectId, query, docId, chunkIndex, requireRating(req.rating()));
    }

    /** Un-vote. Idempotent: clearing a label that is not there is a 200, not a 404. */
    @DeleteMapping("/feedback")
    public void clear(@RequestParam String query,
                      @RequestParam Long projectId,
                      @RequestParam String docId,
                      @RequestParam Integer chunkIndex) {
        repo.clear(requireProject(projectId), requireQuery(query),
                requireDocId(docId), requireChunkIndex(chunkIndex));
    }

    /** Label dump for the UI (to restore thumb state) and for ad-hoc analysis. Newest first. */
    @GetMapping("/feedback")
    public List<FeedbackLabel> list(@RequestParam(required = false) Long projectId,
                                    @RequestParam(required = false) String query,
                                    @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        String q = (query == null || query.isBlank()) ? null : query.strip();
        return repo.list(projectId, q, limit);
    }

    private static String requireQuery(String query) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("query is required");
        String q = query.strip();
        if (q.length() > MAX_QUERY) {
            throw new IllegalArgumentException("query is too long (max " + MAX_QUERY + " characters)");
        }
        return q;
    }

    private long requireProject(Long projectId) {
        if (projectId == null) throw new IllegalArgumentException("projectId is required");
        if (!projects.exists(projectId)) throw new IllegalArgumentException("project not found: " + projectId);
        return projectId;
    }

    private static String requireDocId(String docId) {
        if (docId == null || docId.isBlank()) throw new IllegalArgumentException("docId is required");
        return docId.strip();
    }

    private static int requireChunkIndex(Integer chunkIndex) {
        if (chunkIndex == null || chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must be zero or greater");
        }
        return chunkIndex;
    }

    private static String requireRating(String rating) {
        String r = rating == null ? "" : rating.strip().toLowerCase(Locale.ROOT);
        if (!r.equals("up") && !r.equals("down")) {
            throw new IllegalArgumentException("rating must be 'up' or 'down'");
        }
        return r;
    }
}
