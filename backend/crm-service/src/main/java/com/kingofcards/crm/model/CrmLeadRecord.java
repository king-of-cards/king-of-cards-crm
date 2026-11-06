package com.kingofcards.crm.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** BigQuery lead model, deliberately independent of JPA and PostgreSQL entities. */
public record CrmLeadRecord(UUID leadId, String customerName, String phone, String service,
        LeadStage stage, String source, UUID vendorSubjectId, UUID createdBySubjectId,
        Instant createdAt, Instant updatedAt) {
    public CrmLeadRecord {
        Objects.requireNonNull(leadId, "leadId is required");
        Objects.requireNonNull(customerName, "customerName is required");
        Objects.requireNonNull(phone, "phone is required");
        Objects.requireNonNull(service, "service is required");
        Objects.requireNonNull(stage, "stage is required");
        Objects.requireNonNull(source, "source is required");
        Objects.requireNonNull(vendorSubjectId, "vendorSubjectId is required");
        Objects.requireNonNull(createdBySubjectId, "createdBySubjectId is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        Objects.requireNonNull(updatedAt, "updatedAt is required");
    }

    @Override public String toString() { return "CrmLeadRecord[leadId=" + leadId + ", data=REDACTED]"; }
}
