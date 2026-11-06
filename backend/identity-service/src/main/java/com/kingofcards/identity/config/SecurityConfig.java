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
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    @Bean AccountStatusVerifier accountStatusVerifier(org.springframework.beans.factory.ObjectProvider<com.kingofcards.identity.repository.IdentityUserRepository> users) {
        return new IdentityAccountStatusVerifier(users::getIfAvailable);
    }
    @Bean
    SecurityFilterChain identitySecurityFilterChain(HttpSecurity http, org.springframework.core.env.Environment environment,AccountStatusVerifier verifier) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new IdentityJwtAuthoritiesConverter());
        return http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                // Historical handlers are retained only in an explicit legacy fixture/profile.
                if (environment.acceptsProfiles(org.springframework.core.env.Profiles.of("legacy-vendor-setup")))
                    auth.requestMatchers("/auth/vendor-password-setup").permitAll();
                else auth.requestMatchers("/auth/vendor-password-setup", "/admin/vendors/invitations").denyAll();
                auth
                .requestMatchers("/health", "/actuator/health", "/actuator/health/**",
                    "/auth/login", "/auth/jwks", "/auth/admin-password-setup").permitAll()
                .requestMatchers("/auth/refresh", "/auth/logout").permitAll()
                .requestMatchers("/auth/me").authenticated()
                .requestMatchers("/admin/vendors", "/admin/vendors/**").hasRole("CRM_ADMIN")
                .requestMatchers("/admin/administrators", "/admin/administrators/**").hasRole("CRM_ADMIN")
                .anyRequest().authenticated();
            })
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) ->
                    writeProblem(response, 401, "Unauthorized", "Authentication is required."))
                .accessDeniedHandler((request, response, exception) ->
                    writeProblem(response, 403, "Forbidden", "The request is not allowed.")))
            .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
            .addFilterAfter(new AccountStatusFilter(verifier),org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class)
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
