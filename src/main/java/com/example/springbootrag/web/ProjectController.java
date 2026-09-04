package com.example.springbootrag.web;

import com.example.springbootrag.security.Roles;
import com.example.springbootrag.service.ProjectService;
import org.springframework.security.access.prepost.PreAuthorize;
import com.example.springbootrag.web.dto.ProjectRequest;
import com.example.springbootrag.web.dto.ProjectSummary;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class ProjectController {
    private final ProjectService projects;
    public ProjectController(ProjectService projects) { this.projects = projects; }

    @PostMapping("/projects")
    public Map<String, Long> create(@RequestBody ProjectRequest req) {
        return Map.of("id", projects.create(req.name(), req.groupName()));
    }

    @GetMapping("/projects")
    public List<ProjectSummary> list() { return projects.list(); }

    @PatchMapping("/projects/{id}")
    public void update(@PathVariable long id, @RequestBody java.util.Map<String, Object> body) {
        if (body.containsKey("name")) projects.rename(id, (String) body.get("name"));
        if (body.containsKey("groupName")) projects.setGroup(id, (String) body.get("groupName"));
    }

    /**
     * Destroys the project, every document in it, and its whole quarantine pen (which cascades).
     *
     * <p>Two independent checks, in the pattern quarantine already uses: the role here and on the
     * service, and the read-coverage check inside the service. The service keeps its own copy of
     * the role check so a future injector cannot reach it through a path with no annotation.
     */
    @DeleteMapping("/projects/{id}")
    @PreAuthorize("hasRole('" + Roles.PROJECT_DELETE + "')")
    public void delete(@PathVariable long id) { projects.delete(id); }

    @GetMapping("/groups")
    public List<String> groups() { return projects.groups(); }
}
