package com.kingofcards.identity;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import com.kingofcards.identity.config.AuthCookieSettings;
import com.kingofcards.identity.config.SecurityConfig;
import com.kingofcards.identity.dto.AuthUserResponse;
import com.kingofcards.identity.service.AuthenticationService;

@WebMvcTest(controllers = com.kingofcards.identity.controller.AuthController.class)
@Import({SecurityConfig.class, AuthControllerTest.TestBeans.class,
         com.kingofcards.identity.exception.ApiExceptionHandler.class})
class AuthControllerTest {
    @Autowired MockMvc mvc;
    @Autowired AuthenticationService authentication;

    @Test
    void loginReturnsAccessTokenButNeverPasswordHashOrRefreshTokenBody() throws Exception {
        var user = new AuthUserResponse(UUID.randomUUID(), "employee@example.com", List.of("CRM_ADMIN"));
        when(authentication.login("employee@example.com", "synthetic-password"))
            .thenReturn(new AuthenticationService.LoginResult("access-value", 900, user,
                "refresh-secret", "csrf-token", Duration.ofDays(7)));

        mvc.perform(post("/auth/login").header("Origin", "http://localhost:5173")
                .contentType("application/json")
                .content("{\"email\":\"employee@example.com\",\"password\":\"synthetic-password\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").value("access-value"))
            .andExpect(jsonPath("$.expiresIn").value(900))
            .andExpect(jsonPath("$.user.roles[0]").value("CRM_ADMIN"))
            .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
            .andExpect(jsonPath("$.refreshToken").doesNotExist())
            .andExpect(header().stringValues("Set-Cookie",
                org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("HttpOnly"))));
    }

    @Test
    void rejectsInvalidEmailBeforeAuthentication() throws Exception {
        mvc.perform(post("/auth/login").header("Origin", "http://localhost:5173")
                .contentType("application/json")
                .content("{\"email\":\"invalid\",\"password\":\"synthetic-password\"}"))
            .andExpect(status().isBadRequest());

    }
    @TestConfiguration
    static class TestBeans {
        @Bean AuthenticationService authenticationService() { return mock(AuthenticationService.class); }
        @Bean AuthCookieSettings authCookieSettings() {
            return new AuthCookieSettings(false, "Lax", "http://localhost:5173");
        }
        @Bean JwtDecoder jwtDecoder() {
            return token -> { throw new BadJwtException("No test token expected."); };
        }
    }
}