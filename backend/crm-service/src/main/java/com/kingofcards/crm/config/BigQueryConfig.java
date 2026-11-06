package com.kingofcards.crm.config;

import java.io.IOException;
import java.time.Duration;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.http.HttpTransportOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.kingofcards.crm.service.BigQueryMetadataVerificationService;
import com.kingofcards.crm.bigquery.BigQuerySchemaInitializer;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "crm.bigquery", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(BigQueryProperties.class)
public class BigQueryConfig {
    private static final Logger log = LoggerFactory.getLogger(BigQueryConfig.class);

    @Bean
    BigQuery bigQuery(BigQueryProperties properties) {
        // Retains the exact CRM resource allowlist; this validation never executes DDL.
        properties.validateForSchemaInitialization();
        GoogleCredentials credentials;
        try {
            credentials = GoogleCredentials.getApplicationDefault();
        } catch (IOException exception) {
            // Credential-provider details can include local paths; do not surface them.
            throw new IllegalStateException("BigQuery ADC unavailable. Configure Application Default Credentials.");
        }
        return BigQueryOptions.newBuilder()
            .setProjectId(properties.projectId())
            .setLocation(properties.location())
            .setCredentials(credentials)
            .setTransportOptions(HttpTransportOptions.newBuilder()
                .setConnectTimeout(10_000).setReadTimeout(20_000).build())
            .setRetrySettings(BigQueryOptions.getDefaultRetrySettings().toBuilder()
                .setMaxAttempts(3)
                .setInitialRpcTimeoutDuration(Duration.ofSeconds(20))
                .setMaxRpcTimeoutDuration(Duration.ofSeconds(20))
                .setTotalTimeoutDuration(Duration.ofSeconds(45)).build())
            .build().getService();
    }

    @Bean
    BigQueryMetadataVerificationService bigQueryMetadataVerificationService(
            BigQuery bigQuery, BigQueryProperties properties) {
        return new BigQueryMetadataVerificationService(bigQuery, properties);
    }

    @Bean
    BigQuerySchemaInitializer bigQuerySchemaInitializer(BigQuery bigQuery, BigQueryProperties properties) {
        return new BigQuerySchemaInitializer(bigQuery, properties);
    }

    @Bean
    ApplicationRunner bigQueryResourceValidationRunner(BigQuerySchemaInitializer validator) {
        return arguments -> {
            var result = validator.initialize();
            // Schema structure only; never log rows, descriptions or credential details.
            log.info("BigQuery externally managed resources validated: {}; definition: {}; schema: {}",
                result.status(), result.tableType(), result.schemaDescription());
        };
    }
}
