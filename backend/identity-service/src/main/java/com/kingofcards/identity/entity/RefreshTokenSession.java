package com.kingofcards.identity.entity;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "identity_refresh_session")
public class RefreshTokenSession {
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_family_id", nullable = false)
    private UUID tokenFamilyId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "csrf_token_hash", nullable = false, length = 64)
    private String csrfTokenHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
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
    public UUID getUserId() { return userId; }
    public UUID getTokenFamilyId() { return tokenFamilyId; }
    public String getCsrfTokenHash() { return csrfTokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getUsedAt() { return usedAt; }
    public Instant getRevokedAt() { return revokedAt; }
}