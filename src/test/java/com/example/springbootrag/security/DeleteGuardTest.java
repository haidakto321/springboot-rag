package com.example.springbootrag.security;

import com.example.springbootrag.repository.PgVectorRepository;
import com.example.springbootrag.repository.QuarantineRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rule this guard exists for: you may only destroy what you may read.
 *
 * <p>Deleting is not covered by the read filter that protects every other path - a delete names a
 * row rather than returning it, so an unreadable document could be destroyed by anyone who knew
 * its id. These tests pin the decision itself; the SQL behind the counts is proved against a real
 * database in {@code DeleteAuthorizationIntegrationTest}.
 */
class DeleteGuardTest {

    private static final SearchContext BOB = SearchContext.of("bob", Set.of("public", "eng"));

    private final PgVectorRepository chunks = mock(PgVectorRepository.class);
    private final QuarantineRepository pen = mock(QuarantineRepository.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final DeleteGuard guard = new DeleteGuard(chunks, pen, currentUser);

    @Test
    void aDocumentHoldingAChunkTheCallerCannotReadIsRefused() {
        when(currentUser.contextOrNull()).thenReturn(BOB);
        when(chunks.countUnreadableChunks(BOB, 7L, "Salary-Bands-2026")).thenReturn(3);

        assertThatThrownBy(() -> guard.requireDocumentDeletable(7L, "Salary-Bands-2026"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Salary-Bands-2026");
    }

    @Test
    void aDocumentTheCallerCanFullyReadIsAllowed() {
        when(currentUser.contextOrNull()).thenReturn(BOB);
        when(chunks.countUnreadableChunks(BOB, 7L, "Onboarding-Guide")).thenReturn(0);

        assertThatCode(() -> guard.requireDocumentDeletable(7L, "Onboarding-Guide"))
                .doesNotThrowAnyException();
    }

    @Test
    void aProjectHoldingAnUnreadableQuarantinedDocumentIsRefused() {
        when(currentUser.contextOrNull()).thenReturn(BOB);
        when(chunks.countUnreadableChunks(BOB, 7L)).thenReturn(0);
        when(pen.countUnreadableHeld(BOB, 7L)).thenReturn(1);

        assertThatThrownBy(() -> guard.requireProjectDeletable(7L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aProjectWhoseWholeContentIsReadableIsAllowed() {
        when(currentUser.contextOrNull()).thenReturn(BOB);
        when(chunks.countUnreadableChunks(BOB, 7L)).thenReturn(0);
        when(pen.countUnreadableHeld(BOB, 7L)).thenReturn(0);

        assertThatCode(() -> guard.requireProjectDeletable(7L)).doesNotThrowAnyException();
    }

    @Test
    void theQuarantinePenIsCheckedEvenWhenTheIndexIsEmpty() {
        // A project whose documents are all held has no chunks at all. Counting only chunks would
        // read that as "nothing to protect" and hand over the pen, which holds the ONLY copy of
        // each document in it.
        when(currentUser.contextOrNull()).thenReturn(BOB);
        when(chunks.countUnreadableChunks(BOB, 7L)).thenReturn(0);
        when(pen.countUnreadableHeld(BOB, 7L)).thenReturn(2);

        assertThatThrownBy(() -> guard.requireProjectDeletable(7L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aCallWithNoAuthenticatedPrincipalIsAServerSideCallAndIsAllowed() {
        // Re-ingest, quarantine containment and the wiki importer's async thread all delete without
        // a principal. HTTP cannot reach this branch: the filter chain rejects anonymous requests,
        // which ProjectControllerSecurityTest asserts rather than assumes.
        when(currentUser.contextOrNull()).thenReturn(null);

        assertThatCode(() -> guard.requireDocumentDeletable(7L, "anything")).doesNotThrowAnyException();
        assertThatCode(() -> guard.requireProjectDeletable(7L)).doesNotThrowAnyException();

        verify(chunks, never()).countUnreadableChunks(any(), anyLong(), anyString());
        verify(chunks, never()).countUnreadableChunks(any(), anyLong());
        verify(pen, never()).countUnreadableHeld(any(), anyLong());
    }

    @Test
    void theRefusalNamesWhatItRefusedWithoutNamingWhatWasHidden() {
        when(currentUser.contextOrNull()).thenReturn(BOB);
        when(chunks.countUnreadableChunks(BOB, 7L)).thenReturn(4);
        when(pen.countUnreadableHeld(BOB, 7L)).thenReturn(0);

        assertThatThrownBy(() -> guard.requireProjectDeletable(7L))
                .isInstanceOf(AccessDeniedException.class)
                .satisfies(e -> {
                    // A count is diagnosis. A doc id or a group name would tell the caller what
                    // exists inside a project they are not entitled to read.
                    assertThat(e.getMessage()).contains("7");
                    assertThat(e.getMessage()).doesNotContain("hr");
                });
    }
}
