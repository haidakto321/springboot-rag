package com.example.springbootrag.web;

import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.service.SearchService;
import com.example.springbootrag.service.SearchService.BackendResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/search")
    public List<SearchHit> search(@RequestParam String q,
                                  @RequestParam(defaultValue = "hybrid") String type,
                                  @RequestParam(defaultValue = "10") int topK,
                                  @RequestParam(required = false) List<String> docIds) {
        return searchService.search(type, q, topK, docIds == null ? List.of() : docIds);
    }

    @GetMapping("/compare")
    public Map<String, BackendResult> compare(@RequestParam String q,
                                              @RequestParam(defaultValue = "10") int topK,
                                              @RequestParam(required = false) List<String> docIds) {
        return searchService.compare(q, topK, docIds == null ? List.of() : docIds);
    }
}
