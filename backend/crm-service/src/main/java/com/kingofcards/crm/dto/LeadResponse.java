package com.kingofcards.crm.dto;

import java.time.Instant;
import java.util.UUID;
import com.kingofcards.crm.model.CrmLeadRecord;
import com.kingofcards.crm.model.LeadStage;

public record LeadResponse(UUID id, String name, String phone, String service, LeadStage stage, String source,
        UUID vendorSubjectId, UUID createdBySubjectId, Instant createdAt, Instant updatedAt) {
    public static LeadResponse from(CrmLeadRecord r) {
        return new LeadResponse(r.leadId(), r.customerName(), r.phone(), r.service(), r.stage(), r.source(),
            r.vendorSubjectId(), r.createdBySubjectId(), r.createdAt(), r.updatedAt());
    }
    @Override public String toString() { return "LeadResponse[id=" + id + ", data=REDACTED]"; }
}
