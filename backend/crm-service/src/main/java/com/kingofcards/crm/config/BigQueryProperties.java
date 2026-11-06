package com.kingofcards.crm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Non-secret settings. Credentials are resolved exclusively through ADC.
 * schemaInitEnabled is a legacy ignored flag: schemas are managed externally.
 * metadataVerificationEnabled controls only the legacy dataset-only verifier;
 * enabled BigQuery always performs full read-only resource validation at startup.
 */
@ConfigurationProperties(prefix = "crm.bigquery")
public record BigQueryProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("king-of-cards-509610") String projectId,
        @DefaultValue("shared_rds_data") String dataset,
        @DefaultValue("asia-south1") String location,
        @DefaultValue("crm_leads") String leadsTable,
        @DefaultValue("false") boolean schemaInitEnabled,
        @DefaultValue("false") boolean writesEnabled,
        @DefaultValue("false") boolean metadataVerificationEnabled) {

    public void validateForConnectivity() {
        requireMatch(projectId, "[a-z][a-z0-9-]{4,28}[a-z0-9]", "project-id");
        requireMatch(dataset, "[A-Za-z0-9_]{1,1024}", "dataset");
        requireMatch(leadsTable, "[A-Za-z0-9_]{1,1024}", "leads-table");
        // Syntax validation only; the opt-in dataset check verifies the actual location.
        requireMatch(location, "(?i)(US|EU|[a-z]+(?:-[a-z]+)+[1-9][0-9]*)", "location");
    }

    public void validateForSchemaInitialization() {
        // Legacy method name; identifier validation only, never resource creation.
        validateForConnectivity();
        if (!"king-of-cards-509610".equals(projectId) || !"shared_rds_data".equals(dataset)
                || !"asia-south1".equalsIgnoreCase(location) || !"crm_leads".equals(leadsTable)) {
            throw new IllegalArgumentException(
                "Schema initialization is restricted to king-of-cards-509610.shared_rds_data.crm_leads in asia-south1.");
        }
    }

    private static void requireMatch(String value, String pattern, String setting) {
        if (value == null || !value.matches(pattern)) {
            throw new IllegalArgumentException("Invalid crm.bigquery." + setting + ".");
        }
    }
}
