package com.kingofcards.identity.config;
import java.util.*;
import java.util.function.Supplier;
import com.kingofcards.identity.repository.IdentityUserRepository;
import com.kingofcards.identity.exception.IdentityPersistenceException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

/** Authoritative per-request account state. No cache and no trust in an old role claim. */
public final class IdentityAccountStatusVerifier implements AccountStatusVerifier {
    private final Supplier<IdentityUserRepository> users;
    public IdentityAccountStatusVerifier(Supplier<IdentityUserRepository> users){this.users=users;}
    public void verify(Jwt jwt) {
        UUID id;List<String> roles;
        try{id=UUID.fromString(jwt.getSubject());roles=jwt.getClaimAsStringList("roles");
            if(!id.toString().equals(jwt.getSubject()) || roles==null || roles.size()!=1
                || !Set.of("CRM_ADMIN","CRM_VENDOR").contains(roles.getFirst()))throw denied();
        }catch(RuntimeException exception){throw denied();}
        var repository=users.get();if(repository==null)throw new IdentityPersistenceException("IDENTITY_ACCOUNT_CHECK_UNAVAILABLE");
        var user=repository.findById(id).orElseThrow(IdentityAccountStatusVerifier::denied);
        if(!user.isEnabled() || !user.getId().equals(id)
                || !user.getRoles().stream().map(role->role.getCode()).sorted().toList().equals(roles))throw denied();
    }
    private static AccessDeniedException denied(){return new AccessDeniedException("Account access is not active for this token.");}
}
