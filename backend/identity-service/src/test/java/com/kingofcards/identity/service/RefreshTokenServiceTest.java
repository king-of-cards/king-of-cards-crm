package com.kingofcards.identity.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.kingofcards.identity.config.RefreshSettings;
import com.kingofcards.identity.entity.IdentityUser;
import com.kingofcards.identity.entity.RefreshTokenSession;
import com.kingofcards.identity.exception.*;
import com.kingofcards.identity.repository.RefreshTokenSessionRepository;
import com.kingofcards.identity.repository.RefreshTokenSessionRepository.*;

/** Contract tests only, not a BigQuery concurrency proof. */
class RefreshTokenServiceTest {
    RefreshTokenSessionRepository sessions;
    RefreshTokenService service;
    final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    @BeforeEach void setup() {
        sessions = mock(RefreshTokenSessionRepository.class);
        service = new RefreshTokenService(sessions, new RefreshSettings(Duration.ofDays(7)), Clock.fixed(now, ZoneOffset.UTC));
    }
    @Test void persistsOnlyHashesAndReturnsCredentialsAfterStoreSuccess() {
        var user = user();
        var issued = service.createSession(user);
        var saved = ArgumentCaptor.forClass(RefreshTokenSession.class);
        verify(sessions).createForAuthenticatedUser(eq(user), saved.capture());
        assertNotEquals(issued.refreshToken(), saved.getValue().getTokenHash());
        assertNotEquals(issued.csrfToken(), saved.getValue().getCsrfTokenHash());
        assertEquals(64, saved.getValue().getTokenHash().length());
        assertEquals(user.getId(), saved.getValue().getUserId());
        assertEquals(now.plus(Duration.ofDays(7)), saved.getValue().getExpiresAt());
        assertFalse(issued.toString().contains(issued.refreshToken()));
    }
    @Test void rotationUsesOneAtomicStoreCallWithHashedCredentials() {
        var user = user();
        when(sessions.rotate(anyString(), anyString(), any(), anyString(), anyString(), any(), any()))
            .thenReturn(new Rotation(Status.SUCCESS, user));
        var result = service.rotate("old-refresh", "csrf", "csrf");
        assertEquals(user, result.user());
        assertNotEquals("old-refresh", result.refreshToken());
        assertNotEquals("csrf", result.csrfToken());
        verify(sessions).rotate(matches("[a-f0-9]{64}"), matches("[a-f0-9]{64}"), any(UUID.class),
            matches("[a-f0-9]{64}"), matches("[a-f0-9]{64}"), eq(now), eq(now.plus(Duration.ofDays(7))));
        verifyNoMoreInteractions(sessions);
    }
    @Test void mapsCommittedReuseRevocationTo401WithoutAnotherWrite() {
        when(sessions.rotate(anyString(), anyString(), any(), anyString(), anyString(), any(), any()))
            .thenReturn(new Rotation(Status.REUSED, null));
        assertThrows(RefreshTokenReuseException.class, () -> service.rotate("old", "csrf", "csrf"));
        verify(sessions, times(1)).rotate(anyString(), anyString(), any(), anyString(), anyString(), any(), any());
        verifyNoMoreInteractions(sessions);
    }
    @Test void mapsExpiredOrDisabledSessionToInvalidAuthentication() {
        when(sessions.rotate(anyString(), anyString(), any(), anyString(), anyString(), any(), any()))
            .thenReturn(new Rotation(Status.INVALID, null));
        assertThrows(InvalidRefreshTokenException.class, () -> service.rotate("old", "csrf", "csrf"));
    }
    @Test void rejectsMismatchedOrMissingDoubleSubmitBeforePersistence() {
        assertThrows(CsrfValidationException.class, () -> service.rotate("old", "csrf", null));
        assertThrows(CsrfValidationException.class, () -> service.rotate("old", "csrf", "different"));
        assertThrows(CsrfValidationException.class, () -> service.revoke("old", "csrf", null));
        verifyNoInteractions(sessions);
    }
    @Test void rejectsStoredCsrfHashMismatch() {
        when(sessions.rotate(anyString(), anyString(), any(), anyString(), anyString(), any(), any()))
            .thenReturn(new Rotation(Status.CSRF_REJECTED, null));
        assertThrows(CsrfValidationException.class, () -> service.rotate("old", "csrf", "csrf"));
    }
    @Test void logoutDelegatesAtomicFamilyRevocationWithNoRawTokens() {
        service.revoke("old", "csrf", "csrf");
        verify(sessions).revoke(matches("[a-f0-9]{64}"), matches("[a-f0-9]{64}"), eq(now));
        verifyNoMoreInteractions(sessions);
    }
    @Test void storageFailureNeverReturnsCredentialsOrRetries() {
        doThrow(IdentityPersistenceException.gated()).when(sessions).createForAuthenticatedUser(any(), any());
        assertThrows(IdentityPersistenceException.class, () -> service.createSession(user()));
        verify(sessions, times(1)).createForAuthenticatedUser(any(), any());
    }
    @Test void malformedSuccessCannotAuthorizeDisabledOrMissingUser() {
        assertThrows(IllegalArgumentException.class, () -> new Rotation(Status.SUCCESS, null));
        var disabled = new IdentityUser("x@example.test", "x@example.test", "hash", false);
        assertThrows(IllegalArgumentException.class, () -> new Rotation(Status.SUCCESS, disabled));
    }
    @Test void unknownCommitOutcomeIsPropagatedWithoutRetryingRotationOrLogout() {
        when(sessions.rotate(anyString(), anyString(), any(), anyString(), anyString(), any(), any()))
            .thenThrow(new IdentityPersistenceException("IDENTITY_COMMIT_OUTCOME_UNKNOWN"));
        assertThrows(IdentityPersistenceException.class, () -> service.rotate("old", "csrf", "csrf"));
        verify(sessions, times(1)).rotate(anyString(), anyString(), any(), anyString(), anyString(), any(), any());
        doThrow(new IdentityPersistenceException("IDENTITY_COMMIT_OUTCOME_UNKNOWN"))
            .when(sessions).revoke(anyString(), anyString(), any());
        assertThrows(IdentityPersistenceException.class, () -> service.revoke("old", "csrf", "csrf"));
        verify(sessions, times(1)).revoke(anyString(), anyString(), any());
    }
    private IdentityUser user() { return new IdentityUser("a@example.test", "a@example.test", "hash", true); }
}
