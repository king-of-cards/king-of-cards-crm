package com.kingofcards.identity.entity;

import java.time.Instant;
import java.util.UUID;


public class RefreshTokenSession {


    private UUID id;

    private UUID userId;

    private UUID tokenFamilyId;

    private String tokenHash;

    private String csrfTokenHash;

    private Instant createdAt;

    private Instant expiresAt;

    private Instant usedAt;

    private Instant revokedAt;

    protected RefreshTokenSession() {}

    public RefreshTokenSession(UUID userId, UUID familyId, String tokenHash,
                               String csrfTokenHash, Instant createdAt, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.tokenFamilyId = familyId;
        this.tokenHash = tokenHash;
        this.csrfTokenHash = csrfTokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isExpiredAt(Instant now) { return !expiresAt.isAfter(now); }
    public boolean isConsumedOrRevoked() { return usedAt != null || revokedAt != null; }
    public void consumeAt(Instant now) { usedAt = now; revokedAt = now; }
    public void revokeAt(Instant now) { if (revokedAt == null) revokedAt = now; }
    public String getTokenHash() { return tokenHash; }
    public UUID getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getUserId() { return userId; }
    public UUID getTokenFamilyId() { return tokenFamilyId; }
    public String getCsrfTokenHash() { return csrfTokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getUsedAt() { return usedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    @Override public String toString() { return "RefreshTokenSession[id=" + id + ", data=REDACTED]"; }
}
