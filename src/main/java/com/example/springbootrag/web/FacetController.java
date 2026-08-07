package com.example.springbootrag.web;

import com.example.springbootrag.security.CurrentUser;
import com.example.springbootrag.service.ProjectService;
import com.example.springbootrag.understand.Facet;
import com.example.springbootrag.understand.FacetCatalogue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** What can be filtered on, derived from what is actually indexed. */
@RestController
public class FacetController {

    private final FacetCatalogue catalogue;
    private final ProjectService projectService;
    private final CurrentUser currentUser;

    public FacetController(FacetCatalogue catalogue, ProjectService projectService,
                           CurrentUser currentUser) {
        this.catalogue = catalogue;
        this.projectService = projectService;
        this.currentUser = currentUser;
    }

    @GetMapping("/projects/{projectId}/facets")
    public Map<String, Object> facets(@PathVariable long projectId,
                                      @RequestParam(required = false) String docType) {
        if (!projectService.exists(projectId)) {
            throw new IllegalArgumentException("project not found: " + projectId);
        }
        List<Facet> all = catalogue.forProjects(currentUser.context(), List.of(projectId));
        List<Facet> facets = docType == null || docType.isBlank()
                ? all
                : all.stream().filter(f -> docType.equals(f.docType())).toList();
        List<String> docTypes = all.stream().map(Facet::docType)
                .filter(java.util.Objects::nonNull).distinct().sorted().toList();
        return Map.of("docTypes", docTypes, "facets", facets);
    }
}
