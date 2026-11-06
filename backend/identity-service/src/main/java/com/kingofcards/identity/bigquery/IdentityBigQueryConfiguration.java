package com.kingofcards.identity.bigquery;

import java.io.IOException;
import java.time.Duration;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.*;
import com.google.cloud.http.HttpTransportOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@org.springframework.context.annotation.Import(com.kingofcards.identity.bigquery.production.ProductionAuthConfiguration.class)
@EnableConfigurationProperties(IdentityBigQueryProperties.class)
public class IdentityBigQueryConfiguration {
    @Bean BigQueryIdentityStore identityBigQueryStore(ObjectProvider<BigQuery> clients,
            IdentityBigQueryProperties properties) {
        return new BigQueryIdentityStore(clients::getIfAvailable, properties);
    }
    @Bean
    @ConditionalOnProperty(prefix = "identity.bigquery", name = "enabled", havingValue = "true")
    BigQuery identityBigQuery(IdentityBigQueryProperties properties) {
        properties.validate();
        try {
            // Same approved ADC mechanism as CRM; no copied JSON or gcloud requirement.
            return BigQueryOptions.newBuilder().setProjectId(properties.projectId())
                .setLocation(properties.location()).setCredentials(GoogleCredentials.getApplicationDefault())
                .setTransportOptions(HttpTransportOptions.newBuilder()
                    .setConnectTimeout(10_000).setReadTimeout(20_000).build())
                .setRetrySettings(BigQueryOptions.getDefaultRetrySettings().toBuilder()
                    .setMaxAttempts(3).setTotalTimeoutDuration(Duration.ofSeconds(45)).build())
                .build().getService();
        } catch (IOException exception) {
            throw new IllegalStateException("Identity BigQuery ADC is unavailable.");
        }
    }
    @Bean
    @ConditionalOnProperty(prefix = "identity.bigquery", name = "enabled", havingValue = "true")
    ApplicationRunner identityBigQueryValidation(BigQuery client, IdentityBigQueryProperties properties) {
        return args -> new IdentityBigQueryValidator(client, properties).validate();
    }
}
