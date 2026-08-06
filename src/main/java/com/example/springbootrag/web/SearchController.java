package com.example.springbootrag.web;

import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.repository.MetadataFilter;
import com.example.springbootrag.security.CurrentUser;
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
    private final CurrentUser currentUser;

    public SearchController(SearchService searchService, ProjectService projectService,
                            CurrentUser currentUser) {
        this.searchService = searchService;
        this.projectService = projectService;
        this.currentUser = currentUser;
    }

    /**
     * projectId, group, and docIds are browser-supplied scope: they narrow the result set.
     * The access labels come from {@link CurrentUser} and are not addressable from the request.
     */
    @GetMapping("/search")
    public List<SearchHit> search(@RequestParam String q,
                                  @RequestParam(defaultValue = "hybrid") String type,
                                  @RequestParam(defaultValue = "10") int topK,
                                  @RequestParam(required = false) List<String> docIds,
                                  @RequestParam(required = false) Long projectId,
                                  @RequestParam(defaultValue = "false") boolean group,
                                  @RequestParam(required = false) String docType,
                                  @RequestParam(required = false) String filters) {
        List<Long> scope = projectService.resolveScope(projectId, group);
        return searchService.search(currentUser.context(), type, q, topK, scope,
                docIds == null ? List.of() : docIds, metadataFilter(docType, filters));
    }

    @GetMapping("/compare")
    public Map<String, BackendResult> compare(@RequestParam String q,
                                              @RequestParam(defaultValue = "10") int topK,
                                              @RequestParam(required = false) List<String> docIds,
                                              @RequestParam(required = false) Long projectId,
                                              @RequestParam(defaultValue = "false") boolean group,
                                              @RequestParam(required = false) String docType,
                                              @RequestParam(required = false) String filters) {
        List<Long> scope = projectService.resolveScope(projectId, group);
        return searchService.compare(currentUser.context(), q, topK, scope,
                docIds == null ? List.of() : docIds, metadataFilter(docType, filters));
    }

    /**
     * {@code docType} is a convenience shortcut for the same field inside the filters JSON, so the
     * common case ("only invoices") needs no JSON at all. Malformed filter JSON throws
     * IllegalArgumentException, which {@link GlobalExceptionHandler} maps to 400.
     */
    static MetadataFilter metadataFilter(String docType, String filters) {
        MetadataFilter parsed = MetadataFilter.parse(filters);
        if (docType == null || docType.isBlank()) {
            return parsed;
        }
        return new MetadataFilter(docType, parsed.conditions());
    }
}
