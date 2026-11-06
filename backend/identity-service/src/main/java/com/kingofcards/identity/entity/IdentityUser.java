package com.kingofcards.identity.entity;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;


public class IdentityUser {


    private UUID id;

    private String email;

    private String displayName;

    private String passwordHash;

    private boolean enabled;

    private Instant createdAt;

    private Instant updatedAt;

    private Set<IdentityRole> roles = new HashSet<>();

    protected IdentityUser() {}

    public IdentityUser(String email, String normalizedEmail, String passwordHash, boolean enabled) {
        this.id = UUID.randomUUID();
        this.email = com.kingofcards.identity.service.AuthenticationService.normalizeEmail(email);
        if (!this.email.equals(normalizedEmail))
            throw new IllegalArgumentException("Email must use the authoritative normalization rule.");
        this.passwordHash = passwordHash;
        this.enabled = enabled;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public void addRole(IdentityRole role) {
        if (!IdentityRole.SUPPORTED_CODES.contains(role.getCode()))
            throw new IllegalArgumentException("Unsupported identity role.");
        if (!roles.isEmpty() && roles.stream().noneMatch(r -> r.getCode().equals(role.getCode())))
            throw new IllegalStateException("Multiple roles require an explicit migration decision.");
        if (roles.isEmpty()) roles.add(role);
    }
    /** Rehydrates an externally managed BigQuery row without generating a new subject. */
    public static IdentityUser restore(UUID id, String email, String name, String passwordHash,
            String role, boolean enabled, Instant createdAt, Instant updatedAt) {
        var user = new IdentityUser(email, email, passwordHash, enabled);
        user.id = java.util.Objects.requireNonNull(id);
        user.displayName = name;
        user.createdAt = java.util.Objects.requireNonNull(createdAt);
        user.updatedAt = java.util.Objects.requireNonNull(updatedAt);
        user.addRole(new IdentityRole(role, role));
        return user;
    }
    public UUID getId() { return id; }
    public String getEmail() { return email; }
    /** Compatibility accessor; there is only one stored email value. */
    public String getNormalizedEmail() { return email; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) {
        if (displayName == null || displayName.strip().isEmpty() || displayName.strip().length() > 120) {
            throw new IllegalArgumentException("Display name must contain 1 to 120 characters after trimming.");
        }
        this.displayName = displayName.strip();
    }
    public String getPasswordHash() { return passwordHash; }
    public boolean isEnabled() { return enabled; }
    public Set<IdentityRole> getRoles() { return Set.copyOf(roles); }
    @Override public String toString() { return "IdentityUser[id=" + id + ", data=REDACTED]"; }
}
