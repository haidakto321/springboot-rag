package com.example.springbootrag.service;

import com.example.springbootrag.model.Project;
import com.example.springbootrag.repository.ProjectRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectServiceTest {
    ProjectRepository repo = mock(ProjectRepository.class);
    ProjectService svc = new ProjectService(repo);

    @Test void resolveScopeSingleProjectWhenNotGroup() {
        assertThat(svc.resolveScope(5, false)).containsExactly(5L);
        verify(repo, never()).idsInGroup(any());
    }
    @Test void resolveScopeExpandsToGroupWhenRequested() {
        when(repo.find(5)).thenReturn(Optional.of(new Project(5, "FE", "MyApp")));
        when(repo.idsInGroup("MyApp")).thenReturn(List.of(5L, 6L));
        assertThat(svc.resolveScope(5, true)).containsExactlyInAnyOrder(5L, 6L);
    }
    @Test void resolveScopeGroupFallsBackWhenUngrouped() {
        when(repo.find(5)).thenReturn(Optional.of(new Project(5, "Solo", null)));
        assertThat(svc.resolveScope(5, true)).containsExactly(5L);
    }
    @Test void createRejectsBlankName() {
        assertThatThrownBy(() -> svc.create("  ", null)).isInstanceOf(IllegalArgumentException.class);
    }
}
