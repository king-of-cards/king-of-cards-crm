package com.kingofcards.identity.dto;

import java.util.List;
import java.util.UUID;

public record VendorResponse(UUID subjectId, String displayName, boolean enabled, List<String> roles) {
    public VendorResponse { roles = List.copyOf(roles); }
}
