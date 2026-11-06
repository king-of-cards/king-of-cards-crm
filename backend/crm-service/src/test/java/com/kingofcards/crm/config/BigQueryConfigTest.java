package com.kingofcards.crm.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Date;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.http.HttpTransportOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.kingofcards.crm.service.BigQueryMetadataVerificationService;
import com.kingofcards.crm.bigquery.BigQuerySchemaInitializer;

class BigQueryConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(BigQueryConfig.class);

    @Test
    void absentFlagRequiresNoCredentialsOrGoogleClient() {
        try (var adc = mockStatic(GoogleCredentials.class)) {
            runner.run(context -> {
                assertThat(context).hasNotFailed().doesNotHaveBean(BigQuery.class)
                    .doesNotHaveBean(BigQueryMetadataVerificationService.class)
                    .doesNotHaveBean(BigQuerySchemaInitializer.class)
                    .doesNotHaveBean(ApplicationRunner.class);
                adc.verifyNoInteractions();
            });
        }
    }

    @Test
    void disabledIgnoresUnusedGoogleSettingsAndVerificationFlag() {
        try (var adc = mockStatic(GoogleCredentials.class)) {
            runner.withPropertyValues("crm.bigquery.enabled=false", "crm.bigquery.project-id=invalid",
                    "crm.bigquery.location=invalid", "crm.bigquery.metadata-verification-enabled=true",
                    "crm.bigquery.schema-init-enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(BigQuery.class)
                        .doesNotHaveBean(BigQuerySchemaInitializer.class)
                        .doesNotHaveBean(ApplicationRunner.class);
                    adc.verifyNoInteractions();
                });
        }
    }

    @Test
    void enabledBuildsAdcClientWithoutMakingRequests() throws Exception {
        var credentials = GoogleCredentials.create(new AccessToken("synthetic-unit-test-token",
            Date.from(Instant.now().plusSeconds(3600))));
        try (var adc = mockStatic(GoogleCredentials.class)) {
            adc.when(GoogleCredentials::getApplicationDefault).thenReturn(credentials);
            runner.withPropertyValues("crm.bigquery.enabled=true").run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(BigQuery.class)
                    .hasSingleBean(BigQueryMetadataVerificationService.class)
                    .hasSingleBean(ApplicationRunner.class);
                var options = context.getBean(BigQuery.class).getOptions();
                assertThat(options.getProjectId()).isEqualTo("king-of-cards-509610");
                assertThat(options.getLocation()).isEqualTo("asia-south1");
                assertThat(options.getCredentials()).isSameAs(credentials);
                var transport = (HttpTransportOptions) options.getTransportOptions();
                assertThat(transport.getConnectTimeout()).isEqualTo(10_000);
                assertThat(transport.getReadTimeout()).isEqualTo(20_000);
                assertThat(options.getRetrySettings().getMaxAttempts()).isEqualTo(3);
                assertThatThrownBy(() -> context.getBean(BigQueryMetadataVerificationService.class).verifyDataset())
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("disabled");
                adc.verify(GoogleCredentials::getApplicationDefault);
                adc.verifyNoMoreInteractions();
            });
        }
    }

    @Test
    void legacyMetadataFlagDoesNotAddDuplicateStartupRunners() {
        var credentials = GoogleCredentials.create(new AccessToken("synthetic-unit-test-token",
            Date.from(Instant.now().plusSeconds(3600))));
        try (var adc = mockStatic(GoogleCredentials.class)) {
            adc.when(GoogleCredentials::getApplicationDefault).thenReturn(credentials);
            // ContextRunner does not execute runners; this test makes no metadata request.
            runner.withPropertyValues("crm.bigquery.enabled=true", "crm.bigquery.metadata-verification-enabled=true")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ApplicationRunner.class));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"project-id=Invalid_Project", "project-id=", "dataset=bad.dataset", "dataset=",
        "location=invalid", "location=", "leads-table=bad.table"})
    void invalidOrUnsupportedConfigurationFailsBeforeAdc(String setting) {
        try (var adc = mockStatic(GoogleCredentials.class)) {
            runner.withPropertyValues("crm.bigquery.enabled=true", "crm.bigquery." + setting).run(context -> {
                assertThat(context).hasFailed();
                adc.verifyNoInteractions();
            });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resourceValidationRunnerIsMandatoryRegardlessOfLegacySchemaFlag(boolean legacyFlag) {
        var credentials = GoogleCredentials.create(new AccessToken("synthetic-unit-test-token",
            Date.from(Instant.now().plusSeconds(3600))));
        try (var adc = mockStatic(GoogleCredentials.class)) {
            adc.when(GoogleCredentials::getApplicationDefault).thenReturn(credentials);
            // No ApplicationRunner is executed by ContextRunner: no real table call occurs.
            runner.withPropertyValues("crm.bigquery.enabled=true", "crm.bigquery.schema-init-enabled=" + legacyFlag)
                .run(context -> assertThat(context).hasNotFailed()
                    .hasSingleBean(BigQuerySchemaInitializer.class).hasSingleBean(ApplicationRunner.class));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"leads-table=users", "leads-table=admins", "leads-table=assignments",
        "project-id=another-project", "dataset=another_dataset", "location=us-central1"})
    void resourceValidationRejectsUnapprovedTargetsBeforeAdcEvenWithoutSchemaFlag(String setting) {
        try (var adc = mockStatic(GoogleCredentials.class)) {
            runner.withPropertyValues("crm.bigquery.enabled=true", "crm.bigquery.schema-init-enabled=false",
                    "crm.bigquery." + setting).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "Schema initialization is restricted to king-of-cards-509610.shared_rds_data.crm_leads in asia-south1.");
                adc.verifyNoInteractions();
            });
        }
    }

    @Test
    void startupRunnerPropagatesValidationFailure() {
        var validator = org.mockito.Mockito.mock(BigQuerySchemaInitializer.class);
        org.mockito.Mockito.when(validator.initialize()).thenThrow(new IllegalStateException("Missing required table; provision externally."));
        var startup = new BigQueryConfig().bigQueryResourceValidationRunner(validator);
        assertThatThrownBy(() -> startup.run(new org.springframework.boot.DefaultApplicationArguments()))
            .hasMessageContaining("Missing required table");
        org.mockito.Mockito.verify(validator).initialize();
        org.mockito.Mockito.verifyNoMoreInteractions(validator);
    }

    @Test
    void unavailableAdcProducesActionableErrorWithoutProviderDetails() {
        try (var adc = mockStatic(GoogleCredentials.class)) {
            adc.when(GoogleCredentials::getApplicationDefault)
                .thenThrow(new IOException("synthetic-provider-detail-must-not-leak"));
            runner.withPropertyValues("crm.bigquery.enabled=true").run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "BigQuery ADC unavailable. Configure Application Default Credentials.");
                var trace = new StringWriter();
                context.getStartupFailure().printStackTrace(new PrintWriter(trace));
                assertThat(trace.toString()).doesNotContain("synthetic-provider-detail-must-not-leak");
            });
        }
    }
}
