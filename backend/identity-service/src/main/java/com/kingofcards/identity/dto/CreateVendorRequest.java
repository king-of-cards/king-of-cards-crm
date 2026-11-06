package com.kingofcards.identity.dto;

import java.nio.charset.StandardCharsets;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Role and account status are assigned by the server, never supplied by the caller. */
public record CreateVendorRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 72) String password,
        @NotBlank @Size(max = 120) String displayName) {
    @AssertTrue(message = "Password exceeds the supported limit")
    public boolean isPasswordWithinBcryptLimit() {
        return password != null && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
    @Override public String toString() { return "CreateVendorRequest[data=REDACTED]"; }
}
