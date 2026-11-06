package com.kingofcards.identity.bigquery;

import com.google.cloud.bigquery.*;
import com.kingofcards.identity.exception.IdentityPersistenceException;

/** Read-only metadata validation. Never creates datasets/tables or changes schemas. */
public final class IdentityBigQueryValidator {
    private final BigQuery client;
    private final IdentityBigQueryProperties properties;
    public IdentityBigQueryValidator(BigQuery client, IdentityBigQueryProperties properties) {
        properties.validate();
        this.client = client;
        this.properties = properties;
    }
    public void validate() {
        try {
            var dataset = client.getDataset(DatasetId.of(properties.projectId(), properties.dataset()));
            if (dataset == null) fail("IDENTITY_DATASET_MISSING");
            if (!properties.location().equalsIgnoreCase(dataset.getLocation())) fail("IDENTITY_DATASET_LOCATION_MISMATCH");
            for (var entry : IdentityBigQuerySchema.tables().entrySet()) {
                var table = client.getTable(TableId.of(properties.projectId(), properties.dataset(), entry.getKey()));
                if (table == null) fail("IDENTITY_TABLE_MISSING");
                if (!(table.getDefinition() instanceof StandardTableDefinition definition)
                        || !TableDefinition.Type.TABLE.equals(definition.getType()) || definition.getSchema() == null)
                    fail("IDENTITY_SCHEMA_MISMATCH");
                Schema actual = ((StandardTableDefinition) table.getDefinition()).getSchema();
                for (var expected : entry.getValue().getFields()) {
                    var found = actual.getFields().stream().filter(f -> f.getName().equals(expected.getName())).findFirst();
                    if (found.isEmpty() || !found.get().getType().equals(expected.getType())
                            || mode(found.get()) != mode(expected)) fail("IDENTITY_SCHEMA_MISMATCH");
                }
                for (var field : actual.getFields()) {
                    boolean known=entry.getValue().getFields().stream().anyMatch(expected->expected.getName().equals(field.getName()));
                    if (!known && mode(field)==Field.Mode.REQUIRED) fail("IDENTITY_SCHEMA_MISMATCH");
                }
            }
        } catch (BigQueryException exception) { fail("IDENTITY_BIGQUERY_METADATA_UNAVAILABLE"); }
    }
    private static Field.Mode mode(Field field) { return field.getMode() == null ? Field.Mode.NULLABLE : field.getMode(); }
    private static void fail(String code) { throw new IdentityPersistenceException(code); }
}
