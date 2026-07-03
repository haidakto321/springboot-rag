package com.example.springbootrag.service;

import com.example.springbootrag.model.Project;
import com.example.springbootrag.repository.ProjectRepository;
import com.example.springbootrag.web.dto.ProjectSummary;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProjectService {
    private final ProjectRepository repo;
    public ProjectService(ProjectRepository repo) { this.repo = repo; }

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
    public void delete(long id) { repo.delete(id); }

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

    private static String blankToNull(String s) { return (s == null || s.isBlank()) ? null : s.strip(); }
}
