package com.kingofcards.crm.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain crmSecurityFilterChain(HttpSecurity http,org.springframework.beans.factory.ObjectProvider<AccountStatusVerifier> verifiers) throws Exception {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new JwtRoleAuthoritiesConverter());
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/health", "/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/leads/import", "/leads/import/**").hasRole("CRM_ADMIN")
                .requestMatchers(HttpMethod.POST, "/leads").hasAnyRole("CRM_ADMIN", "CRM_VENDOR")
                .requestMatchers(HttpMethod.GET, "/leads", "/leads/*").hasAnyRole("CRM_ADMIN", "CRM_VENDOR")
                .requestMatchers("/leads", "/leads/**").denyAll()
                .anyRequest().authenticated())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint(problemEntryPoint())
                .accessDeniedHandler(problemDeniedHandler()))
            .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
            .addFilterAfter(new AccountStatusFilter(jwt->{var verifier=verifiers.getIfAvailable();if(verifier==null)throw com.kingofcards.crm.exception.LeadApiException.unavailable();verifier.verify(jwt);}),org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class)
            .build();
    }

    @Bean
    AuthenticationEntryPoint problemEntryPoint() {
        return (request, response, exception) ->
            writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized", "Authentication is required.");
    }

    @Bean
    AccessDeniedHandler problemDeniedHandler() {
        return (request, response, exception) ->
            writeProblem(response, HttpServletResponse.SC_FORBIDDEN, "Forbidden", "The request is not allowed.");
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
