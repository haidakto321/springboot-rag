package com.example.springbootrag.web;

import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.service.ProjectService;
import com.example.springbootrag.service.SearchService;
import com.example.springbootrag.service.SearchService.BackendResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class SearchController {

    private final SearchService searchService;
    private final ProjectService projectService;

    public SearchController(SearchService searchService, ProjectService projectService) {
        this.searchService = searchService;
        this.projectService = projectService;
    }

    @GetMapping("/search")
    public List<SearchHit> search(@RequestParam String q,
                                  @RequestParam(defaultValue = "hybrid") String type,
                                  @RequestParam(defaultValue = "10") int topK,
                                  @RequestParam(required = false) List<String> docIds,
                                  @RequestParam(required = false) Long projectId,
                                  @RequestParam(defaultValue = "false") boolean group) {
        List<Long> scope = projectService.resolveScope(
                projectId != null ? projectId : projectService.defaultProjectId(), group);
        return searchService.search(type, q, topK, scope, docIds == null ? List.of() : docIds);
    }

    @GetMapping("/compare")
    public Map<String, BackendResult> compare(@RequestParam String q,
                                              @RequestParam(defaultValue = "10") int topK,
                                              @RequestParam(required = false) List<String> docIds,
                                              @RequestParam(required = false) Long projectId,
                                              @RequestParam(defaultValue = "false") boolean group) {
        List<Long> scope = projectService.resolveScope(
                projectId != null ? projectId : projectService.defaultProjectId(), group);
        return searchService.compare(q, topK, scope, docIds == null ? List.of() : docIds);
    }
}
