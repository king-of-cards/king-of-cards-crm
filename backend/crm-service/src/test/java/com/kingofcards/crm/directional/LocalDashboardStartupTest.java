package com.kingofcards.crm.directional;
import com.google.cloud.bigquery.BigQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("lead-dashboard-local")
class LocalDashboardStartupTest {
    @Autowired ApplicationContext context;
    @MockitoBean JwtDecoder decoder;
    @Test void explicitProfileUsesTransientRepositoryAndNoCloudClient(){
        assertThat(context.getBean(DirectionalRepository.class)).isInstanceOf(LocalDirectionalRepository.class);
        assertThat(context.getBeansOfType(BigQuery.class)).isEmpty();
        assertThat(context.getEnvironment().getProperty("crm.identity.base-url")).isEqualTo("http://127.0.0.1:18081");
        assertThat(context.getEnvironment().getProperty("crm.jwt.issuer")).isEqualTo("urn:koc:lead-dashboard-local");
    }
}
