package com.example.springbootrag.service;

import com.example.springbootrag.model.Project;
import com.example.springbootrag.repository.ProjectRepository;
import com.example.springbootrag.repository.QdrantRepository;
import com.example.springbootrag.web.dto.ProjectSummary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutionException;

@Service
public class ProjectService {
    private final ProjectRepository repo;
    private final QdrantRepository qdrant;

    public ProjectService(ProjectRepository repo, QdrantRepository qdrant) {
        this.repo = repo;
        this.qdrant = qdrant;
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
        try {
            qdrant.deleteByProject(id);
        } catch (ExecutionException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant project delete failed", e);
        }
        repo.delete(id);
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
