package com.example.springbootrag.service;

import com.example.springbootrag.model.Project;
import com.example.springbootrag.repository.ProjectRepository;
import com.example.springbootrag.repository.QdrantRepository;
import com.example.springbootrag.repository.QuarantineAuditRepository;
import com.example.springbootrag.repository.QuarantineRepository;
import com.example.springbootrag.security.CurrentUser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectServiceTest {
    ProjectRepository repo = mock(ProjectRepository.class);
    QdrantRepository qdrant = mock(QdrantRepository.class);
    QuarantineRepository pen = mock(QuarantineRepository.class);
    QuarantineAuditRepository audit = mock(QuarantineAuditRepository.class);
    CurrentUser currentUser = mock(CurrentUser.class);
    ProjectService svc = new ProjectService(repo, qdrant, pen, audit, currentUser);

    @Test void deleteRecordsEveryHeldDocumentBeforeTheCascadeDestroysIt() throws Exception {
        // Deleting a project cascades the pen. The role gate is nowhere on this path, so the audit
        // row is the only thing that keeps the history honest.
        when(pen.heldForAudit(5)).thenReturn(List.of(
                new QuarantineRepository.PenSummary("policy", "[]", List.of("public")),
                new QuarantineRepository.PenSummary("runbook", "[]", List.of("public"))));
        when(currentUser.principalOrNull()).thenReturn("haiks");
        when(audit.record(anyLong(), any(), any(), any(), any(), any(), any())).thenReturn(1L, 2L);

        svc.delete(5);

        verify(audit).record(5, "policy", "discard", "attempted", "haiks", "[]", List.of("public"));
        verify(audit).record(5, "runbook", "discard", "attempted", "haiks", "[]", List.of("public"));
        verify(audit).outcome(1L, "ok");
        verify(audit).outcome(2L, "ok");
        verify(repo).delete(5);
    }

    @Test void deleteProceedsWhenTheAuditWriteFails() {
        // An audit write must not be able to block a delete the caller is entitled to make.
        when(pen.heldForAudit(5)).thenThrow(new IllegalStateException("audit table gone"));

        svc.delete(5);

        verify(repo).delete(5);
    }

    @Test void resolveScopeSingleProjectWhenNotGroup() {
        assertThat(svc.resolveScope(5, false)).containsExactly(5L);
        verify(repo, never()).idsInGroup(any());
        verify(repo, never()).find(anyLong());
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
    @Test void resolveScopeFallsBackWhenProjectMissing() {
        when(repo.find(9)).thenReturn(Optional.empty());
        assertThat(svc.resolveScope(9, true)).containsExactly(9L);
    }
    @Test void createRejectsBlankName() {
        assertThatThrownBy(() -> svc.create("  ", null)).isInstanceOf(IllegalArgumentException.class);
    }
}
