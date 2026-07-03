package com.example.springbootrag.web;

import com.example.springbootrag.service.ProjectService;
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

    @DeleteMapping("/projects/{id}")
    public void delete(@PathVariable long id) { projects.delete(id); }

    @GetMapping("/groups")
    public List<String> groups() { return projects.groups(); }
}
