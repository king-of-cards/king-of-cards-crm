package com.kingofcards.identity.config;

import static org.junit.jupiter.api.Assertions.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.google.cloud.bigquery.BigQuery;
import com.nimbusds.jose.jwk.JWKSet;
import com.kingofcards.identity.bigquery.BigQueryIdentityStore;
import com.kingofcards.identity.service.JwtTokenService;
import com.kingofcards.identity.service.AuthenticationService;

/** Full mock-web context: no key file, generated key, HTTP server, ADC or database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "identity.bigquery.enabled=false",
    "identity.jwt.issuer=https://identity.example.test",
    "identity.jwt.audience=synthetic-test-audience",
    "identity.jwt.private-key-location=unused-no-file-read",
    "eureka.client.enabled=false", "spring.cloud.discovery.enabled=false",
    "spring.cloud.service-registry.auto-registration.enabled=false"
})
@ActiveProfiles("dev")
class IdentityNoDatabaseStartupTest {
    @Autowired ApplicationContext context;
    @MockitoBean JwtConfig.RsaSigningMaterial material;
    @MockitoBean JWKSet publicJwkSet;
    @MockitoBean JwtEncoder encoder;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean JwtTokenService jwtTokens;

    @Test void startsWithoutAnyRelationalOrGoogleInfrastructure() {
        assertTrue(context.getBeansOfType(com.kingofcards.identity.controller.VendorPasswordSetupController.class).isEmpty());
        assertTrue(context.getBeansOfType(com.kingofcards.identity.service.VendorPasswordSetupService.class).isEmpty());
        assertTrue(context.getBeansOfType(DataSource.class).isEmpty());
        assertFalse(context.containsBean("entityManagerFactory"));
        assertFalse(context.containsBean("flyway"));
        assertTrue(context.getBeansOfType(BigQuery.class).isEmpty());
        assertTrue(context.getBeansOfType(com.kingofcards.identity.bigquery.transactions.BigQueryAuthTransactionAdapter.class).isEmpty());
        assertNotNull(context.getBean(BigQueryIdentityStore.class));
        assertTrue(context.getBeansOfType(com.kingofcards.identity.bigquery.production.ProductionIdentityStore.class).isEmpty());
        assertNotNull(context.getBean(AuthenticationService.class));
        assertNull(context.getEnvironment().getProperty("spring.datasource.url"));
        assertTrue(context.getEnvironment().getProperty("eureka.instance.prefer-ip-address", Boolean.class));
    }
}
