package com.kingofcards.identity.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;

@Configuration
@EnableConfigurationProperties({JwtSettings.class, RefreshSettings.class, AuthCookieSettings.class})
public class JwtConfig {
    @Bean
    RsaSigningMaterial rsaSigningMaterial(JwtSettings settings) throws Exception {
        Path path = Path.of(settings.privateKeyLocation());
        String pem = Files.readString(path);
        byte[] privateBytes = decodePem(pem, "PRIVATE KEY");
        var privateKey = (RSAPrivateCrtKey) KeyFactory.getInstance("RSA")
            .generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
        var publicSpec = new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent());
        var publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(publicSpec);
        RSAKey jwk = new RSAKey.Builder(publicKey).privateKey(privateKey)
            .keyID(keyId(publicKey)).build();
        return new RsaSigningMaterial(publicKey, jwk);
    }

    @Bean
    JWKSet publicJwkSet(RsaSigningMaterial material) {
        return new JWKSet(material.jwk().toPublicJWK());
    }

    @Bean
    JwtEncoder jwtEncoder(RsaSigningMaterial material) {
        JWKSource<SecurityContext> source = new ImmutableJWKSet<>(new JWKSet(material.jwk()));
        return new NimbusJwtEncoder(source);
    }

    @Bean
    JwtDecoder jwtDecoder(RsaSigningMaterial material, JwtSettings settings) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(material.publicKey())
            .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(validators(settings.issuer(), settings.audience()));
        return decoder;
    }

    @Bean
    Clock systemClock() { return Clock.systemUTC(); }

    static OAuth2TokenValidator<Jwt> validators(String issuer, String audience) {
        var issuerAndTime = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> audienceValidator = jwt ->
            jwt.getAudience() != null && jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Required audience is missing", null));
        return new DelegatingOAuth2TokenValidator<>(issuerAndTime, audienceValidator);
    }

    private static byte[] decodePem(String pem, String label) {
        String body = pem.replace("-----BEGIN " + label + "-----", "")
            .replace("-----END " + label + "-----", "");
        return Base64.getMimeDecoder().decode(body);
    }

    private static String keyId(RSAPublicKey key) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create JWT key identifier", e);
        }
    }

    record RsaSigningMaterial(RSAPublicKey publicKey, RSAKey jwk) {
        @Override
        public String toString() { return "RsaSigningMaterial[privateKey=[REDACTED], keyId=" + jwk.getKeyID() + "]"; }
    }
}