package com.kingofcards.identity.config;

import static org.junit.jupiter.api.Assertions.*;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwsHeader;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;

class JwtValidationTest {
    static KeyPair signer;
    static KeyPair other;
    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        signer = generator.generateKeyPair();
        other = generator.generateKeyPair();
    }
    @Test
    void validatesSignatureIssuerAudienceAndExpiry() {
        var decoder = decoder(signer);
        assertEquals("user-1", decoder.decode(token(signer, Instant.now().plusSeconds(60),
            "https://identity.test", "koc-crm")).getSubject());
        assertThrows(JwtException.class, () -> decoder.decode(token(signer, Instant.now().minusSeconds(300),
            "https://identity.test", "koc-crm")));
        assertThrows(JwtException.class, () -> decoder.decode(token(signer, Instant.now().plusSeconds(60),
            "https://wrong.test", "koc-crm")));
        assertThrows(JwtException.class, () -> decoder.decode(token(signer, Instant.now().plusSeconds(60),
            "https://identity.test", "other")));
        assertThrows(JwtException.class, () -> decoder.decode(token(other, Instant.now().plusSeconds(60),
            "https://identity.test", "koc-crm")));
    }
    private static NimbusJwtDecoder decoder(KeyPair pair) {
        var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) pair.getPublic())
            .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(JwtConfig.validators("https://identity.test", "koc-crm"));
        return decoder;
    }
    private static String token(KeyPair pair, Instant expiry, String issuer, String audience) {
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
            .privateKey((java.security.interfaces.RSAPrivateKey) pair.getPrivate()).keyID("test-key").build();
        JWKSource<SecurityContext> source = new ImmutableJWKSet<>(new JWKSet(jwk));
        var encoder = new NimbusJwtEncoder(source);
        Instant issued = expiry.isAfter(Instant.now()) ? Instant.now() : expiry.minusSeconds(60);
        var claims = JwtClaimsSet.builder().issuer(issuer).subject("user-1")
            .audience(List.of(audience)).issuedAt(issued).expiresAt(expiry).build();
        return encoder.encode(JwtEncoderParameters.from(
            JwsHeader.with(SignatureAlgorithm.RS256).keyId("test-key").build(), claims)).getTokenValue();
    }
}