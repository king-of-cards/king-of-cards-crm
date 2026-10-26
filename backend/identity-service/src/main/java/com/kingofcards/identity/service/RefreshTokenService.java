package com.kingofcards.identity.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kingofcards.identity.config.RefreshSettings;
import com.kingofcards.identity.entity.IdentityUser;
import com.kingofcards.identity.entity.RefreshTokenSession;
import com.kingofcards.identity.exception.CsrfValidationException;
import com.kingofcards.identity.exception.InvalidRefreshTokenException;
import com.kingofcards.identity.exception.RefreshTokenReuseException;
import com.kingofcards.identity.repository.IdentityUserRepository;
import com.kingofcards.identity.repository.RefreshTokenSessionRepository;

@Service
public class RefreshTokenService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final RefreshTokenSessionRepository sessions;
    private final IdentityUserRepository users;
    private final RefreshSettings settings;
    private final Clock clock;

    public RefreshTokenService(RefreshTokenSessionRepository sessions, IdentityUserRepository users,
                               RefreshSettings settings, Clock clock) {
        this.sessions = sessions;
        this.users = users;
        this.settings = settings;
        this.clock = clock;
        if (settings.tokenTtl() == null || settings.tokenTtl().isZero() || settings.tokenTtl().isNegative()) {
            throw new IllegalStateException("identity.refresh.token-ttl must be a positive duration");
        }
    }

    @Transactional
    public RefreshCredentials createSession(IdentityUser user) {
        Instant now = clock.instant();
        String refresh = randomToken();
        String csrf = randomToken();
        UUID familyId = UUID.randomUUID();
        sessions.save(new RefreshTokenSession(user.getId(), familyId, sha256(refresh), sha256(csrf),
            now, now.plus(settings.tokenTtl())));
        return new RefreshCredentials(refresh, csrf, settings.tokenTtl());
    }

    @Transactional(noRollbackFor = {InvalidRefreshTokenException.class, RefreshTokenReuseException.class})
    public RotatedCredentials rotate(String rawToken, String csrfCookie, String csrfHeader) {
        if (!validDoubleSubmit(csrfCookie, csrfHeader)) throw new CsrfValidationException();
        Instant now = clock.instant();
        RefreshTokenSession old = sessions.findByTokenHashForUpdate(sha256(rawToken))
            .orElseThrow(InvalidRefreshTokenException::new);
        if (!matchesHash(csrfCookie, old.getCsrfTokenHash())) throw new CsrfValidationException();

        if (old.isConsumedOrRevoked()) {
            sessions.revokeFamily(old.getTokenFamilyId(), now);
            throw new RefreshTokenReuseException();
        }
        if (old.isExpiredAt(now)) {
            sessions.revokeFamily(old.getTokenFamilyId(), now);
            throw new InvalidRefreshTokenException();
        }

        IdentityUser user = users.findById(old.getUserId()).orElseThrow(InvalidRefreshTokenException::new);
        if (!user.isEnabled()) {
            sessions.revokeFamily(old.getTokenFamilyId(), now);
            throw new InvalidRefreshTokenException();
        }

        old.consumeAt(now);
        sessions.save(old);
        String nextRefresh = randomToken();
        String nextCsrf = randomToken();
        sessions.save(new RefreshTokenSession(user.getId(), old.getTokenFamilyId(),
            sha256(nextRefresh), sha256(nextCsrf), now, now.plus(settings.tokenTtl())));
        return new RotatedCredentials(user, nextRefresh, nextCsrf, settings.tokenTtl());
    }

    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public void revoke(String rawToken, String csrfCookie, String csrfHeader) {
        if (!validDoubleSubmit(csrfCookie, csrfHeader)) throw new CsrfValidationException();
        Instant now = clock.instant();
        RefreshTokenSession session = sessions.findByTokenHashForUpdate(sha256(rawToken))
            .orElseThrow(InvalidRefreshTokenException::new);
        if (!matchesHash(csrfCookie, session.getCsrfTokenHash())) throw new CsrfValidationException();
        sessions.revokeFamily(session.getTokenFamilyId(), now);
    }

    private static boolean validDoubleSubmit(String cookie, String header) {
        if (cookie == null || header == null) return false;
        return MessageDigest.isEqual(cookie.getBytes(StandardCharsets.UTF_8),
            header.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean matchesHash(String raw, String expectedHash) {
        return MessageDigest.isEqual(sha256(raw).getBytes(StandardCharsets.US_ASCII),
            expectedHash.getBytes(StandardCharsets.US_ASCII));
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record RefreshCredentials(String refreshToken, String csrfToken, Duration lifetime) {
        @Override public String toString() { return "RefreshCredentials[tokens=[REDACTED], lifetime=" + lifetime + "]"; }
    }
    public record RotatedCredentials(IdentityUser user, String refreshToken, String csrfToken, Duration lifetime) {
        @Override public String toString() { return "RotatedCredentials[tokens=[REDACTED], userId=" + user.getId() + "]"; }
    }
}