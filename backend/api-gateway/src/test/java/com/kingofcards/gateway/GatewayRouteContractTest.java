package com.kingofcards.gateway;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class GatewayRouteContractTest {
    @Test
    void routesAndCredentialedCorsRemainExplicitAndForwardAuthorizationByDefault() throws IOException {
        try (var stream = getClass().getResourceAsStream("/application.yml")) {
            assertNotNull(stream);
            String config = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(config.contains("id: identity-service"));
            assertTrue(config.contains("uri: lb://identity-service"));
            assertTrue(config.contains("Path=/api/identity/**"));
            assertTrue(config.contains("id: crm-service"));
            assertTrue(config.contains("uri: lb://crm-service"));
            assertTrue(config.contains("Path=/api/crm/**"));
            assertTrue(config.contains("StripPrefix=2"));
            assertTrue(config.contains("allowed-origins: ${CORS_ALLOWED_ORIGIN:http://localhost:5173}"));
            assertTrue(config.contains("allowed-headers: Authorization,Content-Type,X-XSRF-TOKEN"));
            assertTrue(config.contains("allow-credentials: true"));
            assertFalse(config.contains("RemoveRequestHeader=Authorization"));
            assertFalse(config.contains("allowed-origin-patterns"));
        }
    }
}