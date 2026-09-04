package com.example.springbootrag.security;

import com.example.springbootrag.repository.PgVectorRepository;
import com.example.springbootrag.repository.QuarantineRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * One rule: you may only destroy what you may read.
 *
 * <p>Every read path in this application is filtered by the caller's groups, so a document outside
 * them is invisible. A delete is not a read - it names a row instead of returning one - so before
 * this guard existed, knowing a doc id was enough to destroy a document you were never allowed to
 * see, and deleting a project destroyed every document in it plus its whole quarantine pen.
 *
 * <p>The check is "does this contain anything unreadable", not "can you read something in it". A
 * document with mixed labels is deleted whole, so partial read access must not authorise it.
 *
 * <p><b>A call with no authenticated principal is allowed through.</b> Re-ingest deletes the old
 * version of a document, quarantine containment un-indexes what it holds, and the wiki importer
 * cleans up from a thread where the security context is empty - none of those are a caller's
 * request. HTTP cannot arrive here without a principal, because the filter chain rejects anonymous
 * requests before a controller runs; {@code ProjectControllerSecurityTest} asserts that premise
 * rather than trusting it, since a later {@code permitAll} would quietly turn this into a bypass.
 */
@Component
public class DeleteGuard {

    private final PgVectorRepository chunks;
    private final QuarantineRepository pen;
    private final CurrentUser currentUser;

    public DeleteGuard(PgVectorRepository chunks, QuarantineRepository pen, CurrentUser currentUser) {
        this.chunks = chunks;
        this.pen = pen;
        this.currentUser = currentUser;
    }

    /** Refuses when any chunk of this document lies outside the caller's groups. */
    public void requireDocumentDeletable(long projectId, String docId) {
        SearchContext ctx = currentUser.contextOrNull();
        if (ctx == null) return;
        int unreadable = chunks.countUnreadableChunks(ctx, projectId, docId);
        if (unreadable > 0) {
            throw new AccessDeniedException("cannot delete document '" + docId + "': it holds "
                    + unreadable + " chunk(s) outside your groups");
        }
    }

    /**
     * Refuses when the project holds any chunk or any quarantined document outside the caller's
     * groups.
     *
     * <p>The pen is counted separately and not as an afterthought: a project whose documents are
     * all held has no chunks at all, so counting only the index would read an unreadable pen as
     * "nothing to protect" - and the pen holds the only copy of each document in it.
     */
    public void requireProjectDeletable(long projectId) {
        SearchContext ctx = currentUser.contextOrNull();
        if (ctx == null) return;
        int unreadable = chunks.countUnreadableChunks(ctx, projectId) + pen.countUnreadableHeld(ctx, projectId);
        if (unreadable > 0) {
            // Deliberately a count and a project id, never a doc id or a group name: the refusal
            // must not describe the contents of something the caller may not read.
            throw new AccessDeniedException("cannot delete project " + projectId + ": it holds "
                    + unreadable + " document(s) or chunk(s) outside your groups");
        }
    }
}
