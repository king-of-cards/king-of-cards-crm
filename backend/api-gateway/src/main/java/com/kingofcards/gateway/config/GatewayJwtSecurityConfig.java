package com.kingofcards.gateway.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class GatewayJwtSecurityConfig {
    @Bean
    ReactiveJwtDecoder gatewayJwtDecoder(@Value("${gateway.jwt.public-key-location}") String location,
            @Value("${gateway.jwt.issuer}") String issuer, @Value("${gateway.jwt.audience}") String audience) throws Exception {
        String pem = Files.readString(Path.of(location));
        if (!pem.contains("-----BEGIN PUBLIC KEY-----") || !pem.contains("-----END PUBLIC KEY-----"))
            throw new IllegalStateException("JWT_PUBLIC_KEY_LOCATION must contain an X.509 RSA PUBLIC KEY PEM.");
        String body = pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "");
        byte[] encoded = Base64.getMimeDecoder().decode(body);
        var key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
        var decoder = NimbusReactiveJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> jwt.getAudience() != null && jwt.getAudience().contains(audience)
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Required audience is missing", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audienceValidator));
        return decoder;
    }

    @Bean
    CorsConfigurationSource gatewayCorsConfigurationSource(GlobalCorsProperties properties) {
        var source = new UrlBasedCorsConfigurationSource();
        source.setCorsConfigurations(properties.getCorsConfigurations());
        return source;
    }

    @Bean
    @Order(0)
    SecurityWebFilterChain gatewaySecurityWebFilterChain(ServerHttpSecurity http, ReactiveJwtDecoder decoder,
            CorsConfigurationSource gatewayCorsConfigurationSource) {
        return http.cors(cors -> cors.configurationSource(gatewayCorsConfigurationSource))
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .authorizeExchange(exchange -> exchange
                .pathMatchers("/api/identity/auth/vendor-password-setup", "/api/identity/admin/vendors/invitations").denyAll()
                .pathMatchers("/api/identity/auth/login", "/api/identity/auth/jwks", "/api/identity/auth/admin-password-setup",
                    "/api/identity/auth/refresh", "/api/identity/auth/logout",
                    "/api/identity/health", "/api/crm/health", "/actuator/health", "/actuator/health/**")
                .permitAll()
                .anyExchange().authenticated())
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtDecoder(decoder)))
            .build();
    }
}
