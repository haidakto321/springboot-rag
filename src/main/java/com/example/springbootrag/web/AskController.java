package com.example.springbootrag.web;

import com.example.springbootrag.service.AskService;
import com.example.springbootrag.web.dto.AskResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AskController {

    private final AskService askService;

    public AskController(AskService askService) {
        this.askService = askService;
    }

    @GetMapping("/ask")
    public AskResponse ask(@RequestParam String q) {
        return askService.ask(q);
    }
}
