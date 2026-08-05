package com.example.springbootrag.security;

import com.example.springbootrag.repository.QdrantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Qdrant half of the access-label migration. Postgres is backfilled by schema.sql; Qdrant has no
 * migration mechanism, so unlabelled points are stamped here at startup.
 *
 * <p>Without this, every point imported before access control (7,536 of them in the wiki corpus)
 * would be invisible to the qdrant backend while still visible to pgvector - a silent, one-sided
 * retrieval regression that looks like a search bug rather than a migration gap.
 *
 * <p>Failure is logged, never fatal: the same posture as {@code ensureCollection}. A machine with
 * Qdrant down should still boot with the Postgres backends working.
 */
@Component
public class QdrantAclBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(QdrantAclBackfill.class);

    private final QdrantRepository qdrant;
    private final SecurityProperties props;

    public QdrantAclBackfill(QdrantRepository qdrant, SecurityProperties props) {
        this.qdrant = qdrant;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isBackfillQdrantGroups()) {
            return;
        }
        try {
            qdrant.backfillAllowedGroups(List.of(props.getDefaultGroup()));
            log.info("Qdrant access-label backfill done: unlabelled points now readable by group '{}'",
                    props.getDefaultGroup());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted during Qdrant access-label backfill", e);
        } catch (Exception e) {
            log.warn("Qdrant access-label backfill failed (Qdrant reachable?). Points without a "
                    + "label stay unreadable through the qdrant backend until this succeeds.", e);
        }
    }
}
