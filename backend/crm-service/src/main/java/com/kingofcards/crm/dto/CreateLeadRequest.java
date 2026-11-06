package com.kingofcards.crm.dto;

import java.util.UUID;
import jakarta.validation.constraints.*;
import com.kingofcards.crm.model.LeadStage;

public record CreateLeadRequest(@NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 40) String phone, @NotBlank @Size(max = 80) String service,
        @NotNull LeadStage stage, @NotBlank @Size(max = 120) String source,
        UUID vendorSubjectId) {
    public CreateLeadRequest {
        name = trim(name); phone = trim(phone); service = trim(service); source = trim(source);
    }
    private static String trim(String value) { return value == null ? null : value.strip(); }
    @Override public String toString() { return "CreateLeadRequest[data=REDACTED]"; }
}
