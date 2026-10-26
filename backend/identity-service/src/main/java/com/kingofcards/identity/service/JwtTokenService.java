package com.kingofcards.identity.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;
import com.kingofcards.identity.config.JwtSettings;
import com.kingofcards.identity.entity.IdentityUser;
import com.nimbusds.jose.jwk.JWKSet;

@Service
public class JwtTokenService {
    private final JwtEncoder encoder;
    private final JwtSettings settings;
    private final Clock clock;
    private final String keyId;

    public JwtTokenService(JwtEncoder encoder, JwtSettings settings, Clock clock, JWKSet publicJwkSet) {
        this.encoder = encoder;
        this.settings = settings;
        this.clock = clock;
        this.keyId = publicJwkSet.getKeys().get(0).getKeyID();
    }

    public IssuedAccessToken issue(IdentityUser user) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(settings.accessTokenTtl());
        List<String> roles = user.getRoles().stream().map(role -> role.getCode()).sorted().toList();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer(settings.issuer())
            .subject(user.getId().toString())
            .audience(List.of(settings.audience()))
            .issuedAt(now)
            .expiresAt(expiresAt)
            .id(UUID.randomUUID().toString())
            .claim("email", user.getEmail())
            .claim("roles", roles)
            .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedAccessToken(value, settings.accessTokenTtl().toSeconds());
    }

    public record IssuedAccessToken(String value, long expiresInSeconds) {}
}