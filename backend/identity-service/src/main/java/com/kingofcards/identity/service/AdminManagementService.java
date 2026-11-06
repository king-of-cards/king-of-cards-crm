package com.kingofcards.identity.service;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import com.kingofcards.identity.exception.VendorDirectoryException;

@Service
@PreAuthorize("hasRole('CRM_ADMIN')")
public class AdminManagementService {
    private final AdminMutationStore store;
    private final Clock clock;
    public AdminManagementService(AdminMutationStore store,Clock clock) { this.store=store; this.clock=clock; }
    public Invitation invite(String email,String name,Authentication authentication) {
        UUID actor=actor(authentication);
        if(email==null || email.length()>254 || !email.strip().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
                || name==null || name.isBlank() || name.strip().length()>120)
            throw new VendorDirectoryException(HttpStatus.BAD_REQUEST,"INVALID_ADMIN","Valid email and name are required.");
        byte[] random=new byte[32];new SecureRandom().nextBytes(random);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Instant expiry=clock.instant().plus(Duration.ofMinutes(30));
        UUID id;
        try { id=store.invite(actor,AuthenticationService.normalizeEmail(email),name.strip(),
            hash("koc-admin-setup\0",token),hash("koc-admin-setup-proof\0",token),expiry); }
        catch(com.kingofcards.identity.exception.IdentityPersistenceException exception) {
            if("DUPLICATE_EMAIL".equals(exception.code()))throw new VendorDirectoryException(HttpStatus.CONFLICT,
                "DUPLICATE_EMAIL","An account already uses this email.");
            throw exception;
        }
        if(id==null)throw new com.kingofcards.identity.exception.IdentityPersistenceException("ADMIN_INVITATION_NOT_CONFIRMED");
        return new Invitation(id,token,expiry);
    }
    public void setEnabled(UUID target,boolean enabled,Authentication authentication) {
        UUID actor=actor(authentication);
        if(!enabled && actor.equals(target))throw new AccessDeniedException("You cannot block your own account.");
        // No SELECT-count-then-save: last-admin and session checks belong in one verified transaction.
        try { store.setEnabled(actor,target,enabled); }
        catch(com.kingofcards.identity.exception.IdentityPersistenceException exception) {
            if("LAST_ACTIVE_ADMIN".equals(exception.code()))throw new VendorDirectoryException(HttpStatus.CONFLICT,
                "LAST_ACTIVE_ADMIN","The last active administrator cannot be blocked.");
            throw exception;
        }
    }
    private static UUID actor(Authentication authentication) {
        if(!(authentication instanceof JwtAuthenticationToken jwt) || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_CRM_ADMIN")))
            throw new AccessDeniedException("Verified CRM administrator required.");
        try { return UUID.fromString(jwt.getToken().getSubject()); }
        catch(RuntimeException exception){throw new AccessDeniedException("Valid account subject required.");}
    }
    private static String hash(String domain,String token) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((domain+token).getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException exception){throw new IllegalStateException("SHA-256 unavailable");}
    }
    public record Invitation(UUID subjectId,String setupToken,Instant expiresAt) {
        @Override public String toString(){return "AdminInvitation[credentials=REDACTED]";}
    }
}
