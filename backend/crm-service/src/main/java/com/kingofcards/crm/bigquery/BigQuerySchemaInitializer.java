package com.kingofcards.crm.bigquery;

import java.util.stream.Collectors;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.Dataset;
import com.google.cloud.bigquery.DatasetId;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableDefinition;
import com.google.cloud.bigquery.TableId;
import com.kingofcards.crm.config.BigQueryProperties;

/**
 * Read-only validation of externally managed resources.
 * The legacy class/method names are retained for compatibility; no creation path exists.
 */
public final class BigQuerySchemaInitializer {
    private final BigQuery client;
    private final BigQueryProperties properties;

    public BigQuerySchemaInitializer(BigQuery client, BigQueryProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public InitializationResult initialize() {
        if (!properties.enabled()) {
            return new InitializationResult(Status.DISABLED, null, null, null);
        }
        properties.validateForSchemaInitialization();
        TableId tableId = TableId.of(properties.projectId(), properties.dataset(), properties.leadsTable());
        String resource = "dataset";
        try {
            Dataset dataset = client.getDataset(DatasetId.of(properties.projectId(), properties.dataset()));
            if (dataset == null) {
                throw new IllegalStateException("BigQuery dataset was not found; provision it externally in the BigQuery Console. No resources were changed.");
            }
            if (!properties.location().equalsIgnoreCase(dataset.getLocation())) {
                throw new IllegalStateException("BigQuery dataset location mismatch; expected asia-south1. No resources were changed.");
            }
            resource = "table";
            Table existing = client.getTable(tableId);
            if (existing == null) {
                throw new IllegalStateException("BigQuery crm_leads table was not found; provision it externally in the BigQuery Console. No resources were changed.");
            }
            return describe(Status.EXISTING, tableId, existing);
        } catch (BigQueryException exception) {
            String permission = resource.equals("dataset") ? "bigquery.datasets.get" : "bigquery.tables.get";
            throw new IllegalStateException("BigQuery " + resource + " metadata validation failed (HTTP " + exception.getCode()
                + "); verify " + permission + " access and resource availability. No resources were changed.");
        }
    }

    private static InitializationResult describe(Status status, TableId tableId, Table table) {
        if (table == null) {
            throw new IllegalStateException("BigQuery table metadata is unavailable; resource validation stopped.");
        }
        TableDefinition definition = table.getDefinition();
        if (definition == null) {
            throw new IllegalStateException("BigQuery table definition is unavailable; no resource repair was attempted.");
        }
        var mismatches = CrmLeadSchema.mismatches(definition);
        if (!mismatches.isEmpty()) {
            throw new IllegalStateException("BigQuery crm_leads schema mismatch; existing resource left unchanged: " + mismatches);
        }
        return new InitializationResult(status, tableId, definition.getType(), definition.getSchema());
    }

    public enum Status { DISABLED, EXISTING }

    public record InitializationResult(Status status, TableId tableId,
            TableDefinition.Type tableType, Schema schema) {
        public String schemaDescription() {
            return schema == null ? "unavailable" : schema.getFields().stream()
                .map(InitializationResult::describeField).collect(Collectors.joining(", ", "[", "]"));
        }

        private static String describeField(Field field) {
            String nested = field.getSubFields() == null ? "" : field.getSubFields().stream()
                .map(InitializationResult::describeField).collect(Collectors.joining(", ", "{", "}"));
            return field.getName() + ":" + field.getType() + ":"
                + (field.getMode() == null ? Field.Mode.NULLABLE : field.getMode()) + nested;
        }
    }
}
