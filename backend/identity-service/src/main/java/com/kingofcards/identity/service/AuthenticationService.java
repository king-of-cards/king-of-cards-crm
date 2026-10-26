package com.kingofcards.identity.service;

import java.util.Locale;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kingofcards.identity.dto.AuthUserResponse;
import com.kingofcards.identity.entity.IdentityUser;
import com.kingofcards.identity.exception.InvalidRefreshTokenException;
import com.kingofcards.identity.exception.RefreshTokenReuseException;
import com.kingofcards.identity.repository.IdentityUserRepository;

@Service
public class AuthenticationService {
    private static final String INVALID_CREDENTIALS = "Invalid email or password";
    private final IdentityUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokens;
    private final RefreshTokenService refreshTokens;
    private final String dummyHash;

    public AuthenticationService(IdentityUserRepository users, PasswordEncoder passwordEncoder,
                                 JwtTokenService jwtTokens, RefreshTokenService refreshTokens) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokens = jwtTokens;
        this.refreshTokens = refreshTokens;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public LoginResult login(String email, String password) {
        String normalized = normalizeEmail(email);
        IdentityUser user = users.findByNormalizedEmail(normalized).orElse(null);
        String hashToCheck = user == null ? dummyHash : user.getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(password, hashToCheck);
        if (user == null || !user.isEnabled() || !passwordMatches) {
            throw new BadCredentialsException(INVALID_CREDENTIALS);
        }
        var access = jwtTokens.issue(user);
        var refresh = refreshTokens.createSession(user);
        return new LoginResult(access.value(), access.expiresInSeconds(), userResponse(user),
            refresh.refreshToken(), refresh.csrfToken(), refresh.lifetime());
    }

    @Transactional(noRollbackFor = {InvalidRefreshTokenException.class, RefreshTokenReuseException.class})
    public RefreshResult refresh(String refreshCookie, String csrfCookie, String csrfHeader) {
        if (refreshCookie == null || refreshCookie.isBlank()) throw new BadCredentialsException("Invalid refresh session");
        var rotated = refreshTokens.rotate(refreshCookie, csrfCookie, csrfHeader);
        var access = jwtTokens.issue(rotated.user());
        return new RefreshResult(access.value(), access.expiresInSeconds(), userResponse(rotated.user()),
            rotated.refreshToken(), rotated.csrfToken(), rotated.lifetime());
    }

    @Transactional
    public void logout(String refreshCookie, String csrfCookie, String csrfHeader) {
        if (refreshCookie == null || refreshCookie.isBlank()) return;
        refreshTokens.revoke(refreshCookie, csrfCookie, csrfHeader);
    }

    public CurrentUser currentUser(UUID id) {
        IdentityUser user = users.findById(id).filter(IdentityUser::isEnabled)
            .orElseThrow(() -> new BadCredentialsException(INVALID_CREDENTIALS));
        return new CurrentUser(user.getId(), user.getEmail(),
            user.getRoles().stream().map(role -> role.getCode()).sorted().toList());
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static AuthUserResponse userResponse(IdentityUser user) {
        return new AuthUserResponse(user.getId(), user.getEmail(),
            user.getRoles().stream().map(role -> role.getCode()).sorted().toList());
    }

    public record LoginResult(String accessToken, long expiresIn, AuthUserResponse user,
                              String refreshToken, String csrfToken, java.time.Duration refreshLifetime) {}
    public record RefreshResult(String accessToken, long expiresIn, AuthUserResponse user,
                                String refreshToken, String csrfToken, java.time.Duration refreshLifetime) {}
    public record CurrentUser(UUID id, String email, java.util.List<String> roles) {}
}