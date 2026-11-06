package com.kingofcards.identity.service;

import java.time.Instant;
import java.util.UUID;

/** New, unverified operations. Implementations must atomically check the enabled actor,
 * unique email, last active admin, target version, and revoke target sessions.
 * A confirmed result is mandatory; unknown commits must never be replayed blindly.
 */
public interface AdminMutationStore {
    /** Only a separately verified implementation may expose setup processing. */
    default boolean setupAvailable() { return false; }
    UUID invite(UUID actor, String email, String name, String tokenHash, String proofHash, Instant expiresAt);
    void setEnabled(UUID actor, UUID target, boolean enabled);
    void completeSetup(String tokenHash, String proofHash, String bcryptHash);
}
