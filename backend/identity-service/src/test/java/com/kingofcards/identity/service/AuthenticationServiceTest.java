package com.kingofcards.identity.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.kingofcards.identity.entity.IdentityRole;
import com.kingofcards.identity.entity.IdentityUser;
import com.kingofcards.identity.repository.IdentityUserRepository;

@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {
    @Mock IdentityUserRepository users;
    @Mock PasswordEncoder encoder;
    @Mock JwtTokenService jwtTokens;
    @Mock RefreshTokenService refreshTokens;
    AuthenticationService service;

    @BeforeEach
    void setUp() {
        when(encoder.encode(anyString())).thenReturn("dummy-hash");
        service = new AuthenticationService(users, encoder, jwtTokens, refreshTokens);
    }

    @Test
    void authenticatesNormalizedEmailAndReturnsNoPasswordMaterial() {
        IdentityUser user = user(true);
        when(users.findByNormalizedEmail("employee@example.com")).thenReturn(Optional.of(user));
        when(encoder.matches("correct", "stored-hash")).thenReturn(true);
        when(jwtTokens.issue(user)).thenReturn(new JwtTokenService.IssuedAccessToken("signed-access-token", 900));
        when(refreshTokens.createSession(user))
            .thenReturn(new RefreshTokenService.RefreshCredentials("opaque-refresh", "csrf-value", Duration.ofDays(7)));
        var result = service.login(" Employee@Example.com ", "correct");
        verify(users).findByNormalizedEmail("employee@example.com");
        assertEquals("signed-access-token", result.accessToken());
        assertEquals("employee@example.com", result.user().email());
        assertFalse(result.toString().contains("correct"));
        assertFalse(result.user().toString().contains("stored-hash"));
    }

    @Test
    void rejectsUnknownUserWithGenericFailure() {
        when(users.findByNormalizedEmail("missing@example.com")).thenReturn(Optional.empty());
        when(encoder.matches("guess", "dummy-hash")).thenReturn(false);
        assertThrows(BadCredentialsException.class, () -> service.login("missing@example.com", "guess"));
    }

    @Test
    void rejectsWrongPasswordAndDisabledUser() {
        IdentityUser user = user(true);
        when(users.findByNormalizedEmail("employee@example.com")).thenReturn(Optional.of(user));
        when(encoder.matches("wrong", "stored-hash")).thenReturn(false);
        assertThrows(BadCredentialsException.class, () -> service.login("employee@example.com", "wrong"));
        IdentityUser disabled = user(false);
        when(users.findByNormalizedEmail("disabled@example.com")).thenReturn(Optional.of(disabled));
        when(encoder.matches("password", "stored-hash")).thenReturn(true);
        assertThrows(BadCredentialsException.class, () -> service.login("disabled@example.com", "password"));
    }

    private static IdentityUser user(boolean enabled) {
        IdentityUser user = new IdentityUser("employee@example.com", "employee@example.com", "stored-hash", enabled);
        user.addRole(new IdentityRole("CRM_AGENT", "CRM Agent"));
        return user;
    }
}