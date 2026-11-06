package com.kingofcards.identity.repository;

import java.time.Instant;
import java.util.UUID;
import com.kingofcards.identity.entity.IdentityUser;
import com.kingofcards.identity.entity.RefreshTokenSession;

/** Whole security transitions, never independent read/update/insert calls.
 * Implementations must return only after a known commit. Reuse/expiry revocation
 * must commit before returning the denial status. No caller-side transaction.
 */
public interface RefreshTokenSessionRepository {
    void createForAuthenticatedUser(IdentityUser expectedUser, RefreshTokenSession session);
    Rotation rotate(String tokenHash, String csrfHash, UUID nextId, String nextTokenHash,
                    String nextCsrfHash, Instant now, Instant nextExpiresAt);
    void revoke(String tokenHash, String csrfHash, Instant now);
    enum Status { SUCCESS, INVALID, REUSED, CSRF_REJECTED }
    record Rotation(Status status, IdentityUser user) {
        public Rotation {
            if (status == null || (status == Status.SUCCESS && (user == null || !user.isEnabled())))
                throw new IllegalArgumentException("Invalid committed refresh result.");
        }
    }
}
