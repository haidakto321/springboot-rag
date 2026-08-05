package com.example.springbootrag.web;

import com.example.springbootrag.security.CurrentUser;
import com.example.springbootrag.service.AskService;
import com.example.springbootrag.service.ProjectService;
import com.example.springbootrag.web.dto.AskResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class AskController {

    private final AskService askService;
    private final ProjectService projectService;
    private final CurrentUser currentUser;

    public AskController(AskService askService, ProjectService projectService, CurrentUser currentUser) {
        this.askService = askService;
        this.projectService = projectService;
        this.currentUser = currentUser;
    }

    @GetMapping("/ask")
    public AskResponse ask(@RequestParam String q,
                           @RequestParam(required = false) Long projectId,
                           @RequestParam(defaultValue = "false") boolean group) {
        List<Long> scope = projectService.resolveScope(projectId, group);
        return askService.ask(currentUser.context(), q, scope);
    }
}
