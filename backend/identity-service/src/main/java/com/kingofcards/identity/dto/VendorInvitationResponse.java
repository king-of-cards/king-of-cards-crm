package com.kingofcards.identity.dto;
import java.time.Instant;
import java.util.UUID;
/** Returned once to an authorized administrator for manual secure delivery. Never log. */
public record VendorInvitationResponse(UUID subjectId, String setupToken, Instant expiresAt) {
    @Override public String toString() { return "VendorInvitationResponse[data=REDACTED]"; }
}
