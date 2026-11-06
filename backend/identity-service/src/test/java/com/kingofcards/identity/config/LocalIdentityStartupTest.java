package com.kingofcards.identity.config;
import com.kingofcards.identity.local.LocalIdentityStore;
import com.google.cloud.bigquery.BigQuery;
import com.kingofcards.identity.config.JwtConfig;
import com.kingofcards.identity.repository.IdentityUserRepository;
import com.kingofcards.identity.service.JwtTokenService;
import com.nimbusds.jose.jwk.JWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.MOCK,properties={"identity.jwt.private-key-location=unused-test-only","KOC_LOCAL_ADMIN_PASSWORD=offline-admin-fixture-only","KOC_LOCAL_VENDOR_PASSWORD=offline-vendor-fixture-only"})
@ActiveProfiles("lead-dashboard-local")
class LocalIdentityStartupTest {
    @Autowired ApplicationContext context;
    @MockitoBean JwtConfig.RsaSigningMaterial material;
    @MockitoBean JWKSet publicJwkSet;
    @MockitoBean JwtEncoder encoder;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean JwtTokenService jwtTokens;
    @Test void localStoreIsPrimaryAndProductionClientsRemainAbsent(){
        assertThat(context.getBean(IdentityUserRepository.class)).isInstanceOf(LocalIdentityStore.class);
        assertThat(context.getBeansOfType(BigQuery.class)).isEmpty();
        assertThat(context.getBean(IdentityUserRepository.class).count()).isEqualTo(3);
        assertThat(context.getEnvironment().getProperty("identity.bigquery.production-mutations-enabled",Boolean.class)).isFalse();
    }
}
