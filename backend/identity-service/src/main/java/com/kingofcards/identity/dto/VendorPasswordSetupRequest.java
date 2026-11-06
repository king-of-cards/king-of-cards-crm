package com.kingofcards.identity.dto;
import java.nio.charset.StandardCharsets;
import jakarta.validation.constraints.*;
public record VendorPasswordSetupRequest(@NotNull @Pattern(regexp="[A-Za-z0-9_-]{43}") String setupToken,
        @NotBlank @Size(min=12,max=72) String password) {
    @AssertTrue(message="Password exceeds the supported limit")
    public boolean isPasswordWithinBcryptLimit() {
        return password != null && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
    @Override public String toString() { return "VendorPasswordSetupRequest[data=REDACTED]"; }
}
