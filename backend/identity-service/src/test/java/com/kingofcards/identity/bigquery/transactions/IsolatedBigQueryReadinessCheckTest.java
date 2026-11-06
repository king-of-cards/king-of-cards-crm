package com.kingofcards.identity.bigquery.transactions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.junit.jupiter.api.Test;
import com.google.cloud.bigquery.*;

class IsolatedBigQueryReadinessCheckTest {
    @Test void failureReportingAllowsOnlyKnownReasonEnumsNeverResponseValues() {
        var body = java.util.Map.of("error", java.util.Map.of("message", "synthetic-sensitive-message", "details",
            java.util.List.of(java.util.Map.of("reason", "SERVICE_DISABLED", "metadata", "synthetic-sensitive-value"))));
        assertEquals("SERVICE_DISABLED", IsolatedBigQueryReadinessCheck.safeReason(body));
        assertEquals("UNCLASSIFIED", IsolatedBigQueryReadinessCheck.safeReason(java.util.Map.of("reason", "synthetic-secret")));
    }
    @Test void metadataProbeNeverSubmitsQueriesOrMutatesAnything() {
        var client = mock(BigQuery.class);
        var dataset = mock(Dataset.class);
        when(dataset.getLocation()).thenReturn("asia-south1");
        when(client.getDataset(any(DatasetId.class))).thenReturn(dataset);
        var lines = IsolatedBigQueryReadinessCheck.metadata(client);
        assertTrue(lines.contains("Dataset location matches asia-south1: true"));
        verify(client).getDataset(DatasetId.of(IsolatedAuthTarget.PROJECT, IsolatedAuthTarget.DATASET));
        verify(client).getTable(TableId.of(IsolatedAuthTarget.PROJECT, IsolatedAuthTarget.DATASET, IsolatedAuthTarget.USERS));
        verify(client).getTable(TableId.of(IsolatedAuthTarget.PROJECT, IsolatedAuthTarget.DATASET, IsolatedAuthTarget.SESSIONS));
        verifyNoMoreInteractions(client);
    }
}
