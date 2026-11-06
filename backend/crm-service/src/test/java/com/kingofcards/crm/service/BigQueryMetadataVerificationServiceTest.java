package com.kingofcards.crm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.Dataset;
import com.google.cloud.bigquery.DatasetId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.kingofcards.crm.config.BigQueryProperties;

class BigQueryMetadataVerificationServiceTest {
    private final BigQuery client = mock(BigQuery.class);
    private final DatasetId datasetId = DatasetId.of("king-of-cards-509610", "shared_rds_data");

    private BigQueryMetadataVerificationService service(boolean enabled, boolean verify) {
        return new BigQueryMetadataVerificationService(client, new BigQueryProperties(enabled,
            "king-of-cards-509610", "shared_rds_data", "asia-south1", "crm_leads", false, false, verify));
    }

    @Test
    void neitherDisabledFeatureNorDisabledVerificationMakesRequests() {
        assertThatThrownBy(() -> service(false, true).verifyDataset()).hasMessageContaining("disabled");
        assertThatThrownBy(() -> service(true, false).verifyDataset()).hasMessageContaining("disabled");
        verifyNoInteractions(client);
    }

    @Test
    void verifiesOnlyExactDatasetMetadata() {
        Dataset dataset = mock(Dataset.class);
        when(dataset.getLocation()).thenReturn("ASIA-SOUTH1");
        when(client.getDataset(datasetId)).thenReturn(dataset);
        var result = service(true, true).verifyDataset();
        assertThat(result.projectId()).isEqualTo("king-of-cards-509610");
        assertThat(result.dataset()).isEqualTo("shared_rds_data");
        assertThat(result.location()).isEqualTo("ASIA-SOUTH1");
        verify(client).getDataset(datasetId);
        verifyNoMoreInteractions(client);
    }

    @Test
    void missingDatasetNeverCreatesResources() {
        assertThatThrownBy(() -> service(true, true).verifyDataset()).hasMessageContaining("not found");
        verify(client).getDataset(datasetId);
        verifyNoMoreInteractions(client);
    }

    @Test
    void wrongLocationFailsWithoutChangingDataset() {
        Dataset dataset = mock(Dataset.class);
        when(dataset.getLocation()).thenReturn("US");
        when(client.getDataset(datasetId)).thenReturn(dataset);
        assertThatThrownBy(() -> service(true, true).verifyDataset()).hasMessageContaining("location does not match");
        verify(client).getDataset(datasetId);
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 404, 503})
    void remoteFailuresAreSanitizedAndNeverTreatedAsPermissionToCreate(int code) {
        when(client.getDataset(datasetId)).thenThrow(new BigQueryException(code, "synthetic-provider-detail"));
        assertThatThrownBy(() -> service(true, true).verifyDataset())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTP " + code)
            .hasMessageNotContaining("synthetic-provider-detail").hasNoCause();
        verify(client).getDataset(datasetId);
        verifyNoMoreInteractions(client);
    }
}
