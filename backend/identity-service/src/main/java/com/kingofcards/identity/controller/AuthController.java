package com.kingofcards.identity.controller;

import java.time.Duration;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.kingofcards.identity.config.AuthCookieSettings;
import com.kingofcards.identity.dto.AuthTokenResponse;
import com.kingofcards.identity.dto.AuthUserResponse;
import com.kingofcards.identity.dto.CurrentUserResponse;
import com.kingofcards.identity.dto.LoginRequest;
import com.kingofcards.identity.exception.InvalidRefreshTokenException;
import com.kingofcards.identity.service.AuthenticationService;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private static final String REFRESH_COOKIE = "KOC_REFRESH";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final String REFRESH_COOKIE_PATH = "/api/identity/auth";
    private final AuthenticationService authentication;
    private final AuthCookieSettings cookies;

    public AuthController(AuthenticationService authentication, AuthCookieSettings cookies) {
        this.authentication = authentication;
        this.cookies = cookies;
        if (!java.util.Set.of("Strict", "Lax", "None").contains(cookies.sameSite())) {
            throw new IllegalStateException("identity.cookie.same-site must be Strict, Lax, or None.");
        }
        if ("None".equals(cookies.sameSite()) && !cookies.secure()) {
            throw new IllegalStateException("SameSite=None requires secure cookies.");
        }
    }

    @PostMapping("/login")
    public AuthTokenResponse login(@Valid @RequestBody LoginRequest request,
                                   HttpServletRequest httpRequest, HttpServletResponse response) {
        validateOrigin(httpRequest);
        var result = authentication.login(request.email(), request.password());
        setCookies(response, result.refreshToken(), result.csrfToken(), result.refreshLifetime());
        return new AuthTokenResponse(result.accessToken(), "Bearer", result.expiresIn(), result.user());
    }

    @GetMapping("/me")
    public CurrentUserResponse me(@AuthenticationPrincipal Jwt jwt) {
        var current = authentication.currentUser(UUID.fromString(jwt.getSubject()));
        return new CurrentUserResponse(current.id(), current.email(), current.roles());
    }

    @PostMapping("/refresh")
    public AuthTokenResponse refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            @CookieValue(name = CSRF_COOKIE, required = false) String csrfCookie,
            @RequestHeader(name = CSRF_HEADER, required = false) String csrfHeader,
            HttpServletRequest request, HttpServletResponse response) {
        validateOrigin(request);
        var result = authentication.refresh(refreshToken, csrfCookie, csrfHeader);
        setCookies(response, result.refreshToken(), result.csrfToken(), result.refreshLifetime());
        return new AuthTokenResponse(result.accessToken(), "Bearer", result.expiresIn(), result.user());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            @CookieValue(name = CSRF_COOKIE, required = false) String csrfCookie,
            @RequestHeader(name = CSRF_HEADER, required = false) String csrfHeader,
            HttpServletRequest request, HttpServletResponse response) {
        validateOrigin(request);
        authentication.logout(refreshToken, csrfCookie, csrfHeader);
        clearCookies(response);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    private void validateOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.equals(cookies.allowedOrigin())) {
            throw new AccessDeniedException("Request origin is not allowed.");
        }
    }

    private void setCookies(HttpServletResponse response, String refresh, String csrf, Duration lifetime) {
        response.addHeader("Set-Cookie", ResponseCookie.from(REFRESH_COOKIE, refresh)
            .httpOnly(true).secure(cookies.secure()).sameSite(cookies.sameSite())
            .path(REFRESH_COOKIE_PATH).maxAge(lifetime).build().toString());
        response.addHeader("Set-Cookie", ResponseCookie.from(CSRF_COOKIE, csrf)
            .httpOnly(false).secure(cookies.secure()).sameSite(cookies.sameSite())
            .path("/").maxAge(lifetime).build().toString());
    }

    private void clearCookies(HttpServletResponse response) {
        response.addHeader("Set-Cookie", ResponseCookie.from(REFRESH_COOKIE, "")
            .httpOnly(true).secure(cookies.secure()).sameSite(cookies.sameSite())
            .path(REFRESH_COOKIE_PATH).maxAge(Duration.ZERO).build().toString());
        response.addHeader("Set-Cookie", ResponseCookie.from(CSRF_COOKIE, "")
            .httpOnly(false).secure(cookies.secure()).sameSite(cookies.sameSite())
            .path("/").maxAge(Duration.ZERO).build().toString());
    }
}