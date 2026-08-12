package com.example.springbootrag.service;

import com.example.springbootrag.model.Project;
import com.example.springbootrag.repository.ProjectRepository;
import com.example.springbootrag.repository.QdrantRepository;
import com.example.springbootrag.repository.QuarantineAuditRepository;
import com.example.springbootrag.repository.QuarantineRepository;
import com.example.springbootrag.security.CurrentUser;
import com.example.springbootrag.web.dto.ProjectSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

@Service
public class ProjectService {
    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    private final ProjectRepository repo;
    private final QdrantRepository qdrant;
    private final QuarantineRepository pen;
    private final QuarantineAuditRepository audit;
    private final CurrentUser currentUser;

    public ProjectService(ProjectRepository repo, QdrantRepository qdrant, QuarantineRepository pen,
                          QuarantineAuditRepository audit, CurrentUser currentUser) {
        this.repo = repo;
        this.qdrant = qdrant;
        this.pen = pen;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    public long create(String name, String groupName) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("project name is required");
        return repo.create(name.strip(), blankToNull(groupName));
    }
    public List<ProjectSummary> list() { return repo.listWithCounts(); }
    public List<String> groups() { return repo.listGroups(); }
    public void rename(long id, String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("project name is required");
        repo.rename(id, name.strip());
    }
    public void setGroup(long id, String groupName) { repo.setGroup(id, blankToNull(groupName)); }
    public void delete(long id) {
        // Deleting a project CASCADES the quarantine pen (quarantine.project_id REFERENCES
        // projects(id) ON DELETE CASCADE), and the pen holds the ONLY copy of every document it
        // contains - they were un-indexed to put them there. So this path destroys held documents
        // just as surely as `discard` does, and it must leave the same record; otherwise the
        // surviving 'held' rows would read as "still contained" forever. quarantine_audit has no
        // foreign key precisely so these rows outlive the project.
        //
        // NOTE: this endpoint carries no role and no group check of its own. That is a real gap,
        // tracked in ROADMAP - it is project-level authorisation, not quarantine's to fix here.
        List<Long> pending = auditPenCascade(id);
        try {
            qdrant.deleteByProject(id);
        } catch (ExecutionException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant project delete failed", e);
        }
        repo.delete(id);
        for (Long auditId : pending) {
            try {
                audit.outcome(auditId, QuarantineAuditRepository.OUTCOME_OK);
            } catch (RuntimeException e) {
                log.error("project {} deleted but audit row {} could not be stamped ok", id, auditId, e);
            }
        }
    }

    /**
     * Records one {@code discard} per held document, before the delete that destroys them.
     *
     * <p>Failure here is logged, not thrown: an audit write must not be able to block a project
     * delete the caller is entitled to make. A row left at {@code attempted} means the delete began
     * and its outcome is unknown, which is the state worth investigating.
     *
     * @return the audit row ids to stamp once the delete has actually happened
     */
    private List<Long> auditPenCascade(long projectId) {
        List<Long> ids = new ArrayList<>();
        try {
            String principal = currentUser.principalOrNull();
            for (QuarantineRepository.PenSummary held : pen.heldForAudit(projectId)) {
                ids.add(audit.record(projectId, held.docId(),
                        QuarantineAuditRepository.ACTION_DISCARD,
                        QuarantineAuditRepository.OUTCOME_ATTEMPTED, principal,
                        held.findingsJson(), held.allowedGroups()));
            }
        } catch (RuntimeException e) {
            log.error("could not record the quarantine cascade for project {}", projectId, e);
        }
        return ids;
    }

    public long defaultProjectId() {
        List<ProjectSummary> all = repo.listWithCounts();
        return all.stream()
            .filter(p -> p.name().equals("Default")).map(ProjectSummary::id).findFirst()
            .orElseGet(() -> all.stream().map(ProjectSummary::id).findFirst()
                .orElseThrow(() -> new IllegalStateException("no projects exist")));
    }

    public List<Long> resolveScope(long projectId, boolean group) {
        if (!group) return List.of(projectId);
        Project p = repo.find(projectId).orElse(null);
        if (p == null || p.groupName() == null || p.groupName().isBlank()) return List.of(projectId);
        List<Long> ids = repo.idsInGroup(p.groupName());
        return ids.isEmpty() ? List.of(projectId) : ids;
    }

    /** Resolves scope for a nullable projectId (null -> the Default project). Per-request DB lookup of the default is acceptable at this app's scale. */
    public List<Long> resolveScope(Long nullableProjectId, boolean group) {
        return resolveScope(nullableProjectId != null ? nullableProjectId : defaultProjectId(), group);
    }

    public boolean exists(long id) { return repo.find(id).isPresent(); }

    private static String blankToNull(String s) { return (s == null || s.isBlank()) ? null : s.strip(); }
}
