package com.kingofcards.identity.config;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain identitySecurityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/health", "/actuator/health", "/actuator/health/**",
                    "/auth/login", "/auth/jwks").permitAll()
                .requestMatchers("/auth/refresh", "/auth/logout").permitAll()
                .requestMatchers("/auth/me").authenticated()
                .anyRequest().authenticated())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) ->
                    writeProblem(response, 401, "Unauthorized", "Authentication is required."))
                .accessDeniedHandler((request, response, exception) ->
                    writeProblem(response, 403, "Forbidden", "The request is not allowed.")))
            .oauth2ResourceServer(resource -> resource.jwt(jwt -> {}))
            .build();
    }
    private static void writeProblem(HttpServletResponse response, int status, String title, String detail)
            throws IOException {
        String body = "{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status +
            ",\"detail\":\"" + detail + "\"}";
        response.setStatus(status);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }
}