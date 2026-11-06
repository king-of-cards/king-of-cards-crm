package com.kingofcards.crm.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.kingofcards.crm.repository.LeadRepository;
import com.kingofcards.crm.service.VendorDirectory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LeadApiConfigTest {
    @Test void defaultApiWiringNeedsNoCredentialsAndDoesNotCreateClient() {
        try(var adc=mockStatic(GoogleCredentials.class)) {
            new ApplicationContextRunner().withUserConfiguration(BigQueryConfig.class,LeadApiConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(LeadRepository.class)
                        .hasSingleBean(VendorDirectory.class).doesNotHaveBean(BigQuery.class);
                    assertThat(context.getBean(BigQueryProperties.class).enabled()).isFalse();
                    assertThat(context.getBean(BigQueryProperties.class).writesEnabled()).isFalse();
                    assertThat(context.getBean(BigQueryProperties.class).schemaInitEnabled()).isFalse();
                    adc.verifyNoInteractions();
                });
        }
    }
}
