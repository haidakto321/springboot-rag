package com.example.springbootrag.web;

import com.example.springbootrag.record.RenderProfile;
import com.example.springbootrag.repository.ProfileRepository;
import com.example.springbootrag.repository.ProfileRepository.StoredProfile;
import com.example.springbootrag.service.ProjectService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Render profiles are configuration, not code: adding one for a new document type is an upsert,
 * not a deploy. This is where a "suggest schema" flow's output would land.
 */
@RestController
public class ProfileController {

    private final ProfileRepository profiles;
    private final ProjectService projectService;

    public ProfileController(ProfileRepository profiles, ProjectService projectService) {
        this.profiles = profiles;
        this.projectService = projectService;
    }

    /** Body is the raw profile JSON; it is parsed before storing so a broken profile fails here. */
    @PutMapping(value = "/projects/{projectId}/profiles/{docType}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> upsert(@PathVariable long projectId, @PathVariable String docType,
                                      @RequestBody String body) {
        requireProject(projectId);
        RenderProfile.parse(body);   // validation only; storage keeps the raw text
        int version = profiles.upsert(projectId, docType, body);
        return Map.of("docType", docType, "version", version);
    }

    @GetMapping("/projects/{projectId}/profiles/{docType}")
    public StoredProfile get(@PathVariable long projectId, @PathVariable String docType) {
        requireProject(projectId);
        return profiles.find(projectId, docType)
                .orElseThrow(() -> new IllegalArgumentException("no profile for docType: " + docType));
    }

    @GetMapping("/projects/{projectId}/profiles")
    public List<StoredProfile> list(@PathVariable long projectId) {
        requireProject(projectId);
        return profiles.list(projectId);
    }

    private void requireProject(long projectId) {
        if (!projectService.exists(projectId)) {
            throw new IllegalArgumentException("project not found: " + projectId);
        }
    }
}
