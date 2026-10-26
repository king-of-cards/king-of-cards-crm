package com.kingofcards.identity.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.kingofcards.identity.config.RefreshSettings;
import com.kingofcards.identity.entity.IdentityUser;
import com.kingofcards.identity.entity.RefreshTokenSession;
import com.kingofcards.identity.exception.CsrfValidationException;
import com.kingofcards.identity.exception.InvalidRefreshTokenException;
import com.kingofcards.identity.exception.RefreshTokenReuseException;
import com.kingofcards.identity.repository.IdentityUserRepository;
import com.kingofcards.identity.repository.RefreshTokenSessionRepository;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {
    @Mock RefreshTokenSessionRepository sessions;
    @Mock IdentityUserRepository users;
    final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    RefreshTokenService service;

    @BeforeEach
    void setUp() {
        service = new RefreshTokenService(sessions, users, new RefreshSettings(Duration.ofDays(7)),
            Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void createsOnlyTokenHashesInPersistence() {
        IdentityUser user = new IdentityUser("a@example.com", "a@example.com", "bcrypt-hash", true);
        var issued = service.createSession(user);
        ArgumentCaptor<RefreshTokenSession> saved = ArgumentCaptor.forClass(RefreshTokenSession.class);
        verify(sessions).save(saved.capture());
        assertNotEquals(issued.refreshToken(), saved.getValue().getTokenHash());
        assertNotEquals(issued.csrfToken(), saved.getValue().getCsrfTokenHash());
        assertEquals(Duration.ofDays(7), issued.lifetime());
    }

    @Test
    void rotatesTokenAndRejectsReuseByRevokingItsFamily() {
        String refresh = "original-refresh";
        String csrf = "original-csrf";
        UUID family = UUID.randomUUID();
        IdentityUser user = new IdentityUser("a@example.com", "a@example.com", "hash", true);
        RefreshTokenSession old = session(refresh, csrf, family, now.plusSeconds(500));
        when(sessions.findByTokenHashForUpdate(hash(refresh))).thenReturn(Optional.of(old));
        when(users.findById(old.getUserId())).thenReturn(Optional.of(user));

        var next = service.rotate(refresh, csrf, csrf);
        assertNotEquals(refresh, next.refreshToken());
        assertNotEquals(csrf, next.csrfToken());
        assertEquals(user, next.user());

        when(sessions.findByTokenHashForUpdate(hash(refresh))).thenReturn(Optional.of(old));
        assertThrows(RefreshTokenReuseException.class, () -> service.rotate(refresh, csrf, csrf));
        verify(sessions).revokeFamily(family, now);
    }

    @Test
    void rejectsExpiredTokensAndMissingCsrf() {
        String refresh = "expired-refresh";
        String csrf = "expired-csrf";
        UUID family = UUID.randomUUID();
        RefreshTokenSession old = session(refresh, csrf, family, now.minusSeconds(1));
        when(sessions.findByTokenHashForUpdate(hash(refresh))).thenReturn(Optional.of(old));
        assertThrows(InvalidRefreshTokenException.class, () -> service.rotate(refresh, csrf, csrf));
        verify(sessions).revokeFamily(family, now);
        assertThrows(CsrfValidationException.class, () -> service.rotate(refresh, csrf, null));
    }

    @Test
    void logoutRevokesTheRefreshFamily() {
        String refresh = "logout-refresh";
        String csrf = "logout-csrf";
        UUID family = UUID.randomUUID();
        when(sessions.findByTokenHashForUpdate(hash(refresh)))
            .thenReturn(Optional.of(session(refresh, csrf, family, now.plusSeconds(500))));
        service.revoke(refresh, csrf, csrf);
        verify(sessions).revokeFamily(family, now);
    }

    private RefreshTokenSession session(String token, String csrf, UUID family, Instant expiry) {
        IdentityUser user = new IdentityUser("a@example.com", "a@example.com", "hash", true);
        return new RefreshTokenSession(user.getId(), family, hash(token), hash(csrf), now.minusSeconds(1), expiry);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}