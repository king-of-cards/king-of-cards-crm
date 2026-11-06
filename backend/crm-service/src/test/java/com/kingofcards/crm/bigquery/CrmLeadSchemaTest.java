package com.kingofcards.crm.bigquery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import java.time.Instant;
import java.util.UUID;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.LegacySQLTypeName;
import com.kingofcards.crm.model.CrmLeadRecord;
import org.junit.jupiter.api.Test;

class CrmLeadSchemaTest {
    @Test
    void validatesTypesModesAndExtraRequiredFieldsWithoutAlteringAnything() {
        var expected = CrmLeadSchema.definition();
        assertThat(CrmLeadSchema.mismatches(com.google.cloud.bigquery.StandardTableDefinition.of(expected))).isEmpty();
        var fields = new java.util.ArrayList<Field>();
        expected.getFields().forEach(fields::add);
        fields.add(Field.of("legacy_optional", com.google.cloud.bigquery.StandardSQLTypeName.STRING));
        assertThat(CrmLeadSchema.mismatches(com.google.cloud.bigquery.StandardTableDefinition.of(com.google.cloud.bigquery.Schema.of(fields)))).isEmpty();
        fields.add(Field.newBuilder("legacy_required", com.google.cloud.bigquery.StandardSQLTypeName.STRING).setMode(Field.Mode.REQUIRED).build());
        assertThat(CrmLeadSchema.mismatches(com.google.cloud.bigquery.StandardTableDefinition.of(com.google.cloud.bigquery.Schema.of(fields)))).contains("Unexpected required column legacy_required");
        fields.set(0, Field.of("lead_id", com.google.cloud.bigquery.StandardSQLTypeName.INT64));
        assertThat(CrmLeadSchema.mismatches(com.google.cloud.bigquery.StandardTableDefinition.of(com.google.cloud.bigquery.Schema.of(fields)))).contains("Type/mode mismatch for lead_id");
    }

    @Test
    void treatsUnspecifiedBigQueryFieldModeAsNullable() {
        var fields = new java.util.ArrayList<Field>();
        for (Field field : CrmLeadSchema.definition().getFields()) {
            fields.add(field.getName().equals("vendor_name")
                ? Field.of("vendor_name", com.google.cloud.bigquery.StandardSQLTypeName.STRING)
                : field);
        }

        assertThat(fields.stream().filter(field -> field.getName().equals("vendor_name")).findFirst().orElseThrow().getMode()).isNull();
        assertThat(CrmLeadSchema.mismatches(com.google.cloud.bigquery.StandardTableDefinition.of(
            com.google.cloud.bigquery.Schema.of(fields)))).isEmpty();
    }
    @Test
    void definesExplicitLeadFieldsAndRequiredModes() {
        var fields = CrmLeadSchema.definition().getFields();
        assertThat(fields).extracting(Field::getName).containsExactly("lead_id", "customer_name", "phone", "service",
            "stage", "source", "vendor_subject_id", "vendor_name", "created_by_subject_id", "created_at", "updated_at");
        assertThat(fields.stream().filter(field -> !field.getName().equals("vendor_name")).map(Field::getMode).toList())
            .containsOnly(Field.Mode.REQUIRED);
        assertThat(fields.stream().filter(field -> field.getName().equals("vendor_name")).map(Field::getMode).toList())
            .containsOnly(Field.Mode.NULLABLE);
        assertThat(fields).extracting(Field::getType).containsExactly(LegacySQLTypeName.STRING,
            LegacySQLTypeName.STRING, LegacySQLTypeName.STRING, LegacySQLTypeName.STRING, LegacySQLTypeName.STRING,
            LegacySQLTypeName.STRING, LegacySQLTypeName.STRING, LegacySQLTypeName.STRING,
            LegacySQLTypeName.STRING, LegacySQLTypeName.TIMESTAMP, LegacySQLTypeName.TIMESTAMP);
    }

    @Test
    void modelRequiresFieldsAndRedactsPersonalData() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-29T00:00:00Z");
        var record = new CrmLeadRecord(id, "Synthetic", "+919876543210", "Photography",
            com.kingofcards.crm.model.LeadStage.CONTACTED, "Referral", id, id, now, now);
        assertThat(record.leadId()).isEqualTo(id);
        assertThat(record.toString()).doesNotContain("Synthetic", "+919876543210");
        assertThatNullPointerException().isThrownBy(() ->
            new CrmLeadRecord(null, "Name", "+919876543210", "Service", com.kingofcards.crm.model.LeadStage.CONTACTED, "Source", id, id, now, now));
        assertThatNullPointerException().isThrownBy(() ->
            new CrmLeadRecord(id, "Name", "+919876543210", "Service", com.kingofcards.crm.model.LeadStage.CONTACTED, "Source", id, id, null, now));
        assertThatNullPointerException().isThrownBy(() ->
            new CrmLeadRecord(id, "Name", "+919876543210", "Service", com.kingofcards.crm.model.LeadStage.CONTACTED, "Source", id, id, now, null));
    }
}
