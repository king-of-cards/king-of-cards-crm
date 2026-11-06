package com.kingofcards.identity.bigquery.production;

import java.time.*;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import com.kingofcards.identity.bigquery.BigQueryIdentityStore;
import com.kingofcards.identity.entity.*;
import com.kingofcards.identity.exception.*;
import com.kingofcards.identity.repository.*;

/** Separate real-account repository. Runtime registration requires explicit,
 * artifact-bound verification and successful synthetic-data/integrity preflight.
 * Each mutation is one confirmed server transaction, never read-then-write.
 */
public final class ProductionIdentityStore implements IdentityUserRepository, RefreshTokenSessionRepository {
    private final BigQueryIdentityStore reads;
    private final ProductionAuthAdapter transactions;
    private final ProductionAuthPlanner planner;
    public ProductionIdentityStore(BigQueryIdentityStore reads, ProductionAuthAdapter transactions, Clock clock) {
        this.reads=Objects.requireNonNull(reads);this.transactions=Objects.requireNonNull(transactions);
        // Explicit approved script budget: the cap covers the whole multi-statement job.
        // The previous 100 MB planner default cannot cover the minimum billed statements.
        this.planner=new ProductionAuthPlanner(Objects.requireNonNull(clock),ProductionAuthPlanner.MAX_BYTES_BILLED);
    }
    @Override public Optional<IdentityUser> findByNormalizedEmail(String email){return reads.findByNormalizedEmail(email);}
    @Override public Optional<IdentityUser> findById(UUID id){return reads.findById(id);}
    @Override public long count(){return reads.count();}
    @Override public Page<IdentityUser> findAssignableVendors(Pageable page){return reads.findAssignableVendors(page);}
    @Override public IdentityUser save(IdentityUser user){
        var result=transactions.submit(planner.updateAccount(UUID.randomUUID(),user));
        rejectDuplicate(result);
        if(result.status()!=ProductionAuthResult.Status.UPDATED)throw new IdentityPersistenceException("IDENTITY_ACCOUNT_UPDATE_NOT_CONFIRMED");
        return Objects.requireNonNull(result.user());
    }
    @Override public void createUnique(IdentityUser user){create(user,false);}
    @Override public void createFirstAdministrator(IdentityUser user){create(user,true);}
    private void create(IdentityUser user, boolean bootstrap){
        var result=transactions.submit(planner.createUser(UUID.randomUUID(),user,bootstrap));
        rejectDuplicate(result);
        if(result.status()==ProductionAuthResult.Status.BOOTSTRAP_NOT_EMPTY)
            throw new IllegalStateException("First administrator requires an empty identity store.");
        if(result.status()!=ProductionAuthResult.Status.CREATED || result.user()==null)
            throw new IdentityPersistenceException("IDENTITY_ACCOUNT_CREATION_NOT_CONFIRMED");
    }
    @Override public void createForAuthenticatedUser(IdentityUser user,RefreshTokenSession session){
        var result=transactions.submit(planner.createSession(UUID.randomUUID(),user,session));
        if(result.status()==ProductionAuthResult.Status.INVALID)throw new InvalidRefreshTokenException();
        if(result.status()!=ProductionAuthResult.Status.CREATED || result.user()==null)
            throw new IdentityPersistenceException("IDENTITY_SESSION_CREATION_NOT_CONFIRMED");
    }
    @Override public Rotation rotate(String token,String csrf,UUID nextId,String nextToken,String nextCsrf,Instant now,Instant expires){
        var result=transactions.submit(planner.rotate(UUID.randomUUID(),token,csrf,nextId,nextToken,nextCsrf,Duration.between(now,expires)));
        var status=switch(result.status()){
            case SUCCESS->Status.SUCCESS;case INVALID->Status.INVALID;
            case REUSED->Status.REUSED;case CSRF_REJECTED->Status.CSRF_REJECTED;
            default->throw new IdentityPersistenceException("IDENTITY_ROTATION_NOT_CONFIRMED");
        };
        return new Rotation(status,result.user());
    }
    @Override public void revoke(String token,String csrf,Instant now){
        var result=transactions.submit(planner.logout(UUID.randomUUID(),token,csrf));
        switch(result.status()){
            case REVOKED->{ }
            case CSRF_REJECTED->throw new CsrfValidationException();
            case INVALID->throw new InvalidRefreshTokenException();
            default->throw new IdentityPersistenceException("IDENTITY_REVOCATION_NOT_CONFIRMED");
        }
    }
    @Override public void createVendorInvitation(IdentityUser user,RefreshTokenSession setup){throw IdentityPersistenceException.gated();}
    @Override public boolean completeVendorPasswordSetup(String token,String proof,String password){throw IdentityPersistenceException.gated();}
    private static void rejectDuplicate(ProductionAuthResult result){
        if(result.status()==ProductionAuthResult.Status.DUPLICATE_EMAIL)
            throw new VendorDirectoryException(HttpStatus.CONFLICT,"DUPLICATE_EMAIL","An account already uses that email.");
    }
}
