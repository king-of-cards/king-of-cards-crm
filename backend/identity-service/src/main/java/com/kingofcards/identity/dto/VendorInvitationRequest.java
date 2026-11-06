package com.kingofcards.identity.dto;
import jakarta.validation.constraints.*;
public record VendorInvitationRequest(@NotBlank @Email @Size(max=254) String email,
        @NotBlank @Size(max=120) String displayName) {
    @Override public String toString() { return "VendorInvitationRequest[data=REDACTED]"; }
}
