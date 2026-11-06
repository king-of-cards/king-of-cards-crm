package com.kingofcards.crm.bigquery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.google.cloud.bigquery.*;
import com.kingofcards.crm.config.BigQueryProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Every successful path is exactly two metadata reads; failures stop earlier. */
class BigQuerySchemaInitializerTest {
    private final BigQuery client = mock(BigQuery.class);
    private final DatasetId datasetId = DatasetId.of("king-of-cards-509610", "shared_rds_data");
    private final TableId tableId = TableId.of("king-of-cards-509610", "shared_rds_data", "crm_leads");
    private BigQueryProperties properties(boolean enabled, boolean legacySchemaFlag, String table) {
        return new BigQueryProperties(enabled, datasetId.getProject(), datasetId.getDataset(), "asia-south1", table, legacySchemaFlag, false, false);
    }
    private BigQuerySchemaInitializer validator(boolean legacyFlag) {
        return new BigQuerySchemaInitializer(client, properties(true, legacyFlag, "crm_leads"));
    }
    private void datasetIn(String location) {
        Dataset dataset = mock(Dataset.class); when(dataset.getLocation()).thenReturn(location);
        when(client.getDataset(datasetId)).thenReturn(dataset);
    }
    private void tableWith(TableDefinition definition) {
        Table table = mock(Table.class); when(table.getDefinition()).thenReturn(definition);
        when(client.getTable(tableId)).thenReturn(table);
    }
    private void verifyOnlyMetadataReads() {
        var ordered = inOrder(client); ordered.verify(client).getDataset(datasetId);
        ordered.verify(client).getTable(tableId); ordered.verifyNoMoreInteractions();
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void disabledBigQueryNeverResolvesResourcesRegardlessOfLegacyFlag(boolean legacyFlag) {
        var result = new BigQuerySchemaInitializer(client, properties(false, legacyFlag, "crm_leads")).initialize();
        assertThat(result.status()).isEqualTo(BigQuerySchemaInitializer.Status.DISABLED); verifyNoInteractions(client);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void enabledAlwaysValidatesExistingTableWithoutMutation(boolean legacyFlag) {
        datasetIn("ASIA-SOUTH1"); tableWith(StandardTableDefinition.of(CrmLeadSchema.definition()));
        var result = validator(legacyFlag).initialize();
        assertThat(result.status()).isEqualTo(BigQuerySchemaInitializer.Status.EXISTING);
        assertThat(result.tableId()).isEqualTo(tableId);
        assertThat(result.schema()).isEqualTo(CrmLeadSchema.definition());
        assertThat(result.schemaDescription()).contains("lead_id:STRING:REQUIRED", "created_at:TIMESTAMP:REQUIRED");
        verifyOnlyMetadataReads();
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void missingTableNeverTriggersCreationEvenWithLegacyFlagTrue(boolean legacyFlag) {
        datasetIn("asia-south1");
        assertThatThrownBy(() -> validator(legacyFlag).initialize())
            .hasMessageContaining("crm_leads table was not found", "BigQuery Console", "No resources were changed");
        verifyOnlyMetadataReads();
    }
    @Test void missingDatasetNeverCreatesAnything() {
        assertThatThrownBy(() -> validator(false).initialize()).hasMessageContaining("dataset was not found", "BigQuery Console");
        verify(client).getDataset(datasetId); verifyNoMoreInteractions(client);
    }
    @ParameterizedTest @ValueSource(strings = {"US", "asia-south2"})
    void wrongLocationStopsBeforeTableLookup(String location) {
        datasetIn(location);
        assertThatThrownBy(() -> validator(false).initialize()).hasMessageContaining("location mismatch", "asia-south1");
        verify(client).getDataset(datasetId); verifyNoMoreInteractions(client);
    }
    @ParameterizedTest @ValueSource(ints = {403, 404, 503})
    void datasetErrorsAreActionableAndSanitized(int status) {
        when(client.getDataset(datasetId)).thenThrow(new BigQueryException(status,"synthetic-sensitive-provider-detail"));
        assertThatThrownBy(() -> validator(false).initialize()).hasMessageContaining("HTTP " + status, "bigquery.datasets.get")
            .hasMessageNotContaining("synthetic-sensitive-provider-detail").hasNoCause();
        verify(client).getDataset(datasetId); verifyNoMoreInteractions(client);
    }
    @ParameterizedTest @ValueSource(ints = {403, 404, 503})
    void tableErrorsNeverTriggerRepairOrCreation(int status) {
        datasetIn("asia-south1");
        when(client.getTable(tableId)).thenThrow(new BigQueryException(status,"synthetic-sensitive-provider-detail"));
        assertThatThrownBy(() -> validator(false).initialize()).hasMessageContaining("HTTP " + status, "bigquery.tables.get")
            .hasMessageNotContaining("synthetic-sensitive-provider-detail").hasNoCause();
        verifyOnlyMetadataReads();
    }
    @Test void incompatibleSchemaStopsWithoutChangingTable() {
        datasetIn("asia-south1"); tableWith(StandardTableDefinition.of(Schema.of(Field.of("legacy_id", StandardSQLTypeName.INT64))));
        assertThatThrownBy(() -> validator(false).initialize()).hasMessageContaining("schema mismatch", "Missing column lead_id");
        verifyOnlyMetadataReads();
    }
    @Test void viewIsNotAcceptedOrReplaced() {
        datasetIn("asia-south1"); tableWith(ViewDefinition.of("SELECT 1"));
        assertThatThrownBy(() -> validator(false).initialize()).hasMessageContaining("schema mismatch"); verifyOnlyMetadataReads();
    }
    @Test void unavailableDefinitionStopsWithoutModification() {
        datasetIn("asia-south1"); tableWith(null);
        assertThatThrownBy(() -> validator(false).initialize()).hasMessageContaining("definition is unavailable"); verifyOnlyMetadataReads();
    }
    @ParameterizedTest @ValueSource(strings = {"users", "admins", "assignments"})
    void unrelatedTablesAreRejectedBeforeAnyRequest(String table) {
        assertThatThrownBy(() -> new BigQuerySchemaInitializer(client,properties(true,false,table)).initialize()).hasMessageContaining("restricted");
        verifyNoInteractions(client);
    }
}