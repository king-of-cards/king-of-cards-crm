package com.kingofcards.crm;

import static org.assertj.core.api.Assertions.assertThat;
import javax.sql.DataSource;
import org.springframework.util.ClassUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.google.cloud.bigquery.BigQuery;
import com.kingofcards.crm.controller.LeadController;
import com.kingofcards.crm.repository.LeadRepository;

/** Full mock-web context; no HTTP server, Google credentials, discovery or database connection. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "crm.bigquery.enabled=false",
    "crm.jwt.issuer=https://identity.example.test",
    "crm.jwt.audience=synthetic-test-audience",
    "crm.jwt.jwk-set-uri=https://identity.example.test/auth/jwks",
    "eureka.client.enabled=false",
    "spring.cloud.discovery.enabled=false",
    "spring.cloud.service-registry.auto-registration.enabled=false"
})
@ActiveProfiles("dev")
class CrmNoJdbcStartupTest {
    @Autowired ApplicationContext context;
    @MockitoBean JwtDecoder decoder;

    @Test void startsWithNoRelationalDependenciesOrInfrastructure() {
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(ClassUtils.isPresent("jakarta.persistence.EntityManagerFactory", getClass().getClassLoader())).isFalse();
        assertThat(ClassUtils.isPresent("org.flywaydb.core.Flyway", getClass().getClassLoader())).isFalse();
        assertThat(ClassUtils.isPresent("org.postgresql.Driver", getClass().getClassLoader())).isFalse();
        assertThat(ClassUtils.isPresent("org.h2.Driver", getClass().getClassLoader())).isFalse();
        assertThat(context.containsBean("entityManagerFactory")).isFalse();
        assertThat(context.getBeansOfType(BigQuery.class)).isEmpty();
        assertThat(context.getBean(LeadRepository.class)).isNotNull();
        assertThat(context.getBean(LeadController.class)).isNotNull();
        assertThat(context.getEnvironment().getProperty("spring.datasource.url")).isNull();
        assertThat(context.getEnvironment().getProperty("spring.flyway.enabled")).isNull();
        assertThat(context.getEnvironment().getProperty("eureka.instance.prefer-ip-address", Boolean.class)).isTrue();
    }
}
