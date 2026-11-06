package com.kingofcards.identity.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.kingofcards.identity.entity.IdentityUser;
import com.kingofcards.identity.entity.RefreshTokenSession;

/** Explicit BigQuery contract. No check-then-insert or JPA transaction semantics. */
public interface IdentityUserRepository {
    Optional<IdentityUser> findByNormalizedEmail(String normalizedEmail);
    Optional<IdentityUser> findById(UUID id);
    long count();
    Page<IdentityUser> findAssignableVendors(Pageable pageable);
    /** Atomic version-checked account update and session revocation; never an upsert. Currently gated. */
    IdentityUser save(IdentityUser user);
    /** Atomic unique-email creation, not a preflight SELECT followed by INSERT. */
    void createUnique(IdentityUser user);
    /** Atomically verify an empty identity store and create its first admin. */
    void createFirstAdministrator(IdentityUser user);
    /** Atomic unique account plus domain-separated setup credential, confirmed commit only. */
    void createVendorInvitation(IdentityUser user, RefreshTokenSession setup);
    /** Atomically consume one unexpired setup credential, change password and revoke all sessions.
     * false covers unknown/expired/replayed credentials or disabled/non-vendor accounts.
     */
    boolean completeVendorPasswordSetup(String tokenHash, String proofHash, String passwordHash);
}
