package com.kingofcards.identity.service;

import java.time.Instant;
import java.util.UUID;
import com.kingofcards.identity.exception.IdentityPersistenceException;
import org.springframework.stereotype.Component;

/** No configuration switch can silently route Admin invitations through Vendor mutations. */
@Component
public final class UnverifiedAdminMutationStore implements AdminMutationStore {
    private static IdentityPersistenceException blocked() { return new IdentityPersistenceException("ADMIN_MUTATIONS_UNVERIFIED"); }
    public UUID invite(UUID actor,String email,String name,String tokenHash,String proofHash,Instant expiresAt) { throw blocked(); }
    public void setEnabled(UUID actor,UUID target,boolean enabled) { throw blocked(); }
    public void completeSetup(String tokenHash,String proofHash,String bcryptHash) { throw blocked(); }
}
