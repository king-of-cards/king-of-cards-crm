package com.kingofcards.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VendorDisplayNameRequest(@NotBlank @Size(max = 120) String displayName) {
    public VendorDisplayNameRequest {
        displayName = displayName == null ? null : displayName.strip();
    }
}
