package com.kingofcards.crm.bigquery;

import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.StandardSQLTypeName;
import com.google.cloud.bigquery.TableDefinition;
import java.util.ArrayList;
import java.util.List;

/** Applied only to a newly created crm_leads table, never to an existing resource. */
public final class CrmLeadSchema {
    private CrmLeadSchema() {}

    public static Schema definition() {
        return Schema.of(
            field("lead_id", StandardSQLTypeName.STRING, Field.Mode.REQUIRED),
            field("customer_name", StandardSQLTypeName.STRING, Field.Mode.REQUIRED),
            field("phone", StandardSQLTypeName.STRING, Field.Mode.REQUIRED),
            field("service", StandardSQLTypeName.STRING, Field.Mode.REQUIRED),
            field("stage", StandardSQLTypeName.STRING, Field.Mode.REQUIRED),
            field("source", StandardSQLTypeName.STRING, Field.Mode.REQUIRED),
            field("vendor_subject_id", StandardSQLTypeName.STRING, Field.Mode.REQUIRED),
            field("vendor_name", StandardSQLTypeName.STRING, Field.Mode.NULLABLE),
            field("created_by_subject_id", StandardSQLTypeName.STRING, Field.Mode.REQUIRED),
            field("created_at", StandardSQLTypeName.TIMESTAMP, Field.Mode.REQUIRED),
            field("updated_at", StandardSQLTypeName.TIMESTAMP, Field.Mode.REQUIRED));
    }

    /** Compare query/insert compatibility without modifying metadata; ignore descriptions/order. */
    public static List<String> mismatches(TableDefinition actual) {
        if (actual == null || actual.getType() != TableDefinition.Type.TABLE || actual.getSchema() == null) {
            return List.of("Expected a standard table with a schema");
        }
        var errors = new ArrayList<String>();
        for (Field expected : definition().getFields()) {
            Field found = actual.getSchema().getFields().stream()
                .filter(f -> f.getName().equals(expected.getName())).findFirst().orElse(null);
            if (found == null) errors.add("Missing column " + expected.getName());
            else {
                // BigQuery metadata may omit mode for nullable fields. Treat that representation
                // as NULLABLE, matching the semantics used by DirectionalSchema.require.
                Field.Mode actualMode = found.getMode() == null ? Field.Mode.NULLABLE : found.getMode();
                Field.Mode expectedMode = expected.getMode() == null ? Field.Mode.NULLABLE : expected.getMode();
                if (!found.getType().equals(expected.getType()) || actualMode != expectedMode) {
                    errors.add("Type/mode mismatch for " + expected.getName());
                }
            }
        }
        for (Field found : actual.getSchema().getFields()) {
            boolean known = definition().getFields().stream().anyMatch(f -> f.getName().equals(found.getName()));
            if (!known && found.getMode() == Field.Mode.REQUIRED) errors.add("Unexpected required column " + found.getName());
        }
        return List.copyOf(errors);
    }

    private static Field field(String name, StandardSQLTypeName type, Field.Mode mode) {
        return Field.newBuilder(name, type).setMode(mode).build();
    }
}
