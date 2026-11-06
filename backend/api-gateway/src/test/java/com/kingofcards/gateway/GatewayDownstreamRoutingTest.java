package com.kingofcards.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Only disposable loopback HTTP fixtures. Never contacts the live services or BigQuery. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1", "eureka.client.enabled=false",
    "spring.cloud.service-registry.auto-registration.enabled=false",
    "spring.cloud.gateway.server.webflux.discovery.locator.enabled=false"
})
class GatewayDownstreamRoutingTest {
    private static final String ISSUER = "urn:koc:gateway-routing-test";
    private static final KeyPair KEYS;
    private static final Path PUBLIC_KEY;
    private static final HttpServer BACKEND;
    private static final BlockingQueue<Received> RECEIVED = new LinkedBlockingQueue<>();
    record Received(String method, String path, String query, String authorization, String body) {}
    static {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KEYS = generator.generateKeyPair();
            PUBLIC_KEY = Files.createTempFile("koc-routing-public-", ".pem");
            Files.writeString(PUBLIC_KEY, "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(KEYS.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n", StandardCharsets.US_ASCII);
            BACKEND = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            BACKEND.createContext("/", exchange -> {
                var path = exchange.getRequestURI().getPath();
                RECEIVED.add(new Received(exchange.getRequestMethod(), path,
                    exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                int status = path.equals("/auth/login") ? 503 : 200;
                byte[] body = (status == 503 ? "{\"code\":\"FIXTURE_UNAVAILABLE\"}" : "{\"status\":\"UP\"}")
                    .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
                exchange.close();
            });
            BACKEND.start();
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.jwt.public-key-location", () -> PUBLIC_KEY.toString());
        registry.add("gateway.jwt.issuer", () -> ISSUER);
        registry.add("gateway.jwt.audience", () -> "routing-test");
        for (String service : List.of("identity-service", "crm-service")) {
            registry.add("spring.cloud.discovery.client.simple.instances." + service + "[0].uri",
                () -> "http://127.0.0.1:" + BACKEND.getAddress().getPort());
        }
    }
    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    @BeforeEach void clear() { RECEIVED.clear(); }
    @AfterAll static void cleanup() throws Exception { BACKEND.stop(0); Files.deleteIfExists(PUBLIC_KEY); }

    @Test void repeatedlyRoutesBothHealthPathsThroughLoadBalancerAndStripPrefix() throws Exception {
        for (int i = 0; i < 16; i++) {
            String service = i % 2 == 0 ? "identity" : "crm";
            var response = request("GET", "/api/" + service + "/health", null, null);
            assertEquals(200, response.statusCode());
            assertEquals("/health", RECEIVED.poll(2, TimeUnit.SECONDS).path());
        }
        // All bounded requests reached the fixture once, with no outstanding duplicate delivery.
        assertTrue(RECEIVED.isEmpty());
    }

    @Test void forwardsValidatedJwtPathAndQueryToBothServicesUnchanged() throws Exception {
        String token = token();
        for (String path : List.of("/api/identity/auth/me", "/api/crm/directional/leads?vendorSubjectId=fixture&page=2")) {
            assertEquals(200, request("GET", path, "Bearer " + token, null).statusCode());
            var received = RECEIVED.poll(2, TimeUnit.SECONDS);
            assertNotNull(received);
            assertEquals("Bearer " + token, received.authorization());
            assertEquals(path.substring(path.indexOf('/', 5)), received.path()
                + (received.query() == null ? "" : "?" + received.query()));
        }
    }

    @Test void missingOrInvalidJwtNeverReachesDownstream() throws Exception {
        for (String path : List.of("/api/identity/auth/me", "/api/crm/directional/leads")) {
            assertEquals(401, request("GET", path, null, null).statusCode());
            assertEquals(401, request("GET", path, "Bearer invalid-fixture-token", null).statusCode());
        }
        assertTrue(RECEIVED.isEmpty());
    }

    @Test void downstreamErrorAndSyntheticBodyAreForwardedWithoutAutomaticLoginRetry() throws Exception {
        // This request reaches only the disposable fixture, not Identity or any session store.
        var response = request("POST", "/api/identity/auth/login", null, "{\"fixture\":true}");
        assertEquals(503, response.statusCode());
        assertEquals("{\"code\":\"FIXTURE_UNAVAILABLE\"}", response.body());
        var received = RECEIVED.poll(2, TimeUnit.SECONDS);
        assertNotNull(received);
        assertEquals("POST", received.method());
        assertEquals("/auth/login", received.path());
        assertEquals("{\"fixture\":true}", received.body());
        assertNull(RECEIVED.poll(150, TimeUnit.MILLISECONDS));
    }

    @Test void approvedCorsSurvivesRealDownstreamRouting() throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/identity/health"))
            .timeout(Duration.ofSeconds(5)).header("Origin", "http://localhost:5173").GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("http://localhost:5173", response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
    }

    private HttpResponse<String> request(String method, String path, String token, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(5)).method(method, body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) builder.header("Authorization", token);
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static String token() {
        var key = new RSAKey.Builder((RSAPublicKey) KEYS.getPublic()).privateKey(KEYS.getPrivate()).build();
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        var claims = JwtClaimsSet.builder().issuer(ISSUER).audience(List.of("routing-test"))
            .subject("synthetic-fixture").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(120))
            .claim("roles", List.of("CRM_ADMIN")).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }
}
