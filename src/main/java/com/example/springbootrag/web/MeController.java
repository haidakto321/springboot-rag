package com.example.springbootrag.web;

import com.example.springbootrag.security.CurrentUser;
import com.example.springbootrag.security.SearchContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who the server thinks you are. The UI shows this so the identity driving retrieval is visible
 * rather than implied - "why can't I see that document" should be answerable in one glance.
 */
@RestController
public class MeController {

    private final CurrentUser currentUser;

    public MeController(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    @GetMapping("/me")
    public SearchContext me() {
        return currentUser.context();
    }
}
