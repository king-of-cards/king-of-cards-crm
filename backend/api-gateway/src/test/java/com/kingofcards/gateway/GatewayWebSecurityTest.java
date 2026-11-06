package com.kingofcards.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

/** Real local HTTP/security filter checks; Eureka and all remote backends are disabled. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "eureka.client.enabled=false", "spring.cloud.discovery.enabled=false",
    "spring.cloud.service-registry.auto-registration.enabled=false",
    "spring.cloud.gateway.server.webflux.discovery.locator.enabled=false"
})
class GatewayWebSecurityTest {
    private static final String ISSUER = "http://localhost:8081";
    private static final String AUDIENCE = "king-of-cards-crm";
    private static final KeyPair KEYS;
    private static final Path PUBLIC_KEY;
    static {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KEYS = generator.generateKeyPair();
            PUBLIC_KEY = Files.createTempFile("koc-gateway-http-test-", ".pem");
            String body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(KEYS.getPublic().getEncoded());
            Files.writeString(PUBLIC_KEY, "-----BEGIN PUBLIC KEY-----\n" + body
                + "\n-----END PUBLIC KEY-----\n", StandardCharsets.US_ASCII);
        } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }

    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.jwt.public-key-location", () -> PUBLIC_KEY.toString());
        registry.add("gateway.jwt.issuer", () -> ISSUER);
        registry.add("gateway.jwt.audience", () -> AUDIENCE);
    }

    @AfterAll static void cleanup() throws Exception { Files.deleteIfExists(PUBLIC_KEY); }

    @Test void protectedRequestsRejectMissingAndMalformedTokens() throws Exception {
        assertEquals(401, request("GET", "/api/crm/leads", Map.of()).statusCode());
        assertEquals(401, request("GET", "/api/identity/admin/vendors",
            Map.of("Authorization", "Bearer invalid-test-token")).statusCode());
    }
    @Test void adminSetupTokenEndpointReachesRoutingWithoutPublicRegistration() throws Exception {
        // Discovery is disabled: 503 proves routing, not backend validation or setup success.
        assertEquals(503,request("POST","/api/identity/auth/admin-password-setup",Map.of("Content-Type","application/json")).statusCode());
        assertEquals(401,request("POST","/api/identity/admin/administrators/invitations",Map.of()).statusCode());
    }

    @Test void verifiesSignatureIssuerAudienceAndExpiryBeforeRouting() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair wrongKeys = generator.generateKeyPair();
        for (String token : List.of(token(wrongKeys, ISSUER, AUDIENCE, Instant.now().plusSeconds(120)),
                token(KEYS, "https://wrong.example", AUDIENCE, Instant.now().plusSeconds(120)),
                token(KEYS, ISSUER, "wrong-audience", Instant.now().plusSeconds(120)),
                token(KEYS, ISSUER, AUDIENCE, Instant.now().minusSeconds(120)))) {
            assertEquals(401, request("GET", "/__security_probe__",
                Map.of("Authorization", "Bearer " + token)).statusCode());
        }
        // A valid JWT reaches routing, where this deliberately nonexistent path is 404.
        assertEquals(404, request("GET", "/__security_probe__", Map.of("Authorization", "Bearer "
            + token(KEYS, ISSUER, AUDIENCE, Instant.now().plusSeconds(120)))).statusCode());
    }

    @Test void allowsCredentialedBrowserPreflightForProtectedRoutes() throws Exception {
        var response = request("OPTIONS", "/api/crm/leads", Map.of(
            "Origin", "http://localhost:5173", "Access-Control-Request-Method", "GET",
            "Access-Control-Request-Headers", "authorization,content-type"));
        assertEquals(200, response.statusCode());
        assertEquals("http://localhost:5173", response.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        assertEquals("true", response.headers().firstValue("Access-Control-Allow-Credentials").orElse(""));
    }

    @Test void rejectsUnapprovedBrowserOrigin() throws Exception {
        assertEquals(403, request("OPTIONS", "/api/crm/leads", Map.of(
            "Origin", "https://unapproved.example", "Access-Control-Request-Method", "GET")).statusCode());
    }

    @Test void legacyInvitationAndSetupPathsAreNotActiveEvenForAdmin() throws Exception {
        for (String path : List.of("/api/identity/admin/vendors/invitations", "/api/identity/auth/vendor-password-setup")) {
            assertEquals(401, request("POST",path,Map.of()).statusCode());
            assertEquals(403, request("POST",path,Map.of("Authorization","Bearer "
                + token(KEYS,ISSUER,AUDIENCE,Instant.now().plusSeconds(120)))).statusCode());
        }
    }

    private HttpResponse<String> request(String method, String path, Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(10)).method(method, HttpRequest.BodyPublishers.noBody());
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String token(KeyPair keys, String issuer, String audience, Instant expires) {
        var rsa = new RSAKey.Builder((RSAPublicKey) keys.getPublic()).privateKey(keys.getPrivate()).build();
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsa)));
        var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience))
            .subject("00000000-0000-4000-8000-000000000001").issuedAt(expires.minusSeconds(120))
            .expiresAt(expires).claim("roles", List.of("CRM_ADMIN")).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
            .getTokenValue();
    }
}
