package com.example.springbootrag.web;

import com.example.springbootrag.security.CurrentUser;
import com.example.springbootrag.trace.RagTrace;
import com.example.springbootrag.trace.TraceRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Reads back the {@code rag_trace} rows behind recent answers (RAG-MASTERY section 6).
 *
 * <p>Scoped to the caller's own traces, always. A trace holds the question someone typed and the
 * documents it matched, so an unscoped list would hand over exactly what the access filter exists
 * to protect - including the titles of documents the reader cannot open.
 */
@RestController
public class TraceController {

    static final int MAX_LIMIT = 50;

    private final TraceRepository repo;
    private final CurrentUser currentUser;

    public TraceController(TraceRepository repo, CurrentUser currentUser) {
        this.repo = repo;
        this.currentUser = currentUser;
    }

    @GetMapping("/traces")
    public List<RagTrace> recent(@RequestParam(defaultValue = "10") int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        return repo.recent(currentUser.context().principal(), limit);
    }
}
