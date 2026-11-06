package com.kingofcards.gateway.config;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.jwk.source.*;

class GatewayJwtSecurityConfigTest {
    private static final String ISSUER="http://localhost:8081";
    private static final String AUDIENCE="king-of-cards-crm";

    @Test void acceptsOnlyMatchingRsaSignatureIssuerAndAudience() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        var pair=generator.generateKeyPair();
        Path publicKey=Files.createTempFile("koc-gateway-public-", ".pem");
        try {
            String body=Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(pair.getPublic().getEncoded());
            Files.writeString(publicKey,"-----BEGIN PUBLIC KEY-----\n"+body+"\n-----END PUBLIC KEY-----\n",StandardCharsets.US_ASCII);
            var decoder=new GatewayJwtSecurityConfig().gatewayJwtDecoder(publicKey.toString(),ISSUER,AUDIENCE);
            assertEquals(ISSUER,decoder.decode(token(pair,ISSUER,AUDIENCE,Instant.now().plusSeconds(60))).block().getIssuer().toString());
            assertThrows(RuntimeException.class,()->decoder.decode(token(pair,ISSUER,"other",Instant.now().plusSeconds(60))).block());
            assertThrows(RuntimeException.class,()->decoder.decode(token(pair,"https://wrong.example",AUDIENCE,Instant.now().plusSeconds(60))).block());
            assertThrows(RuntimeException.class,()->decoder.decode(token(pair,ISSUER,AUDIENCE,Instant.now().minusSeconds(60))).block());
        } finally { Files.deleteIfExists(publicKey); }
    }

    @Test void rejectsPrivateKeyPemAtPublicKeySetting() throws Exception {
        Path invalid=Files.createTempFile("koc-gateway-invalid-key-", ".pem");
        try {
            Files.writeString(invalid,"-----BEGIN PRIVATE KEY-----\nZm9v\n-----END PRIVATE KEY-----\n");
            assertThrows(IllegalStateException.class,()->new GatewayJwtSecurityConfig()
                .gatewayJwtDecoder(invalid.toString(),ISSUER,AUDIENCE));
        } finally { Files.deleteIfExists(invalid); }
    }

    private static String token(java.security.KeyPair pair,String issuer,String audience,Instant expiry) {
        var rsa=new RSAKey.Builder((RSAPublicKey)pair.getPublic()).privateKey(pair.getPrivate()).build();
        JWKSource<SecurityContext> source=new ImmutableJWKSet<>(new JWKSet(rsa));
        var encoder=new NimbusJwtEncoder(source);
        var claims=JwtClaimsSet.builder().issuer(issuer).subject("synthetic-test-subject")
            .audience(List.of(audience)).issuedAt(expiry.minusSeconds(120)).expiresAt(expiry).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),claims)).getTokenValue();
    }

}
