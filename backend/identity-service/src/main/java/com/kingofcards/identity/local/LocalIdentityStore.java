package com.kingofcards.identity.local;

import java.time.*;
import java.util.*;
import com.kingofcards.identity.entity.*;
import com.kingofcards.identity.repository.*;
import com.kingofcards.identity.exception.*;
import com.kingofcards.identity.dto.*;
import org.springframework.data.domain.*;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Explicit, transient LOCAL test adapter only; never a distributed persistence guarantee.
 * Real controllers, BCrypt, JWTs, cookies and CSRF execute normally over this state.
 */
public final class LocalIdentityStore implements IdentityUserRepository,RefreshTokenSessionRepository {
    private final Map<UUID,IdentityUser> users=new LinkedHashMap<>();private final Map<String,Session> sessions=new HashMap<>();private final Clock clock;
    private static final class Session {
        UUID id,user,family;String hash,csrf;Instant created,expires,used,revoked;
        Session(UUID id,UUID user,UUID family,String hash,String csrf,Instant created,Instant expires){this.id=id;this.user=user;this.family=family;this.hash=hash;this.csrf=csrf;this.created=created;this.expires=expires;}
    }
    public LocalIdentityStore(PasswordEncoder passwords,Clock clock,String adminPassword,String vendorPassword){
        this.clock=clock;validatePassword(adminPassword);validatePassword(vendorPassword);
        seed("local-admin@example.test","Local Admin","CRM_ADMIN",passwords.encode(adminPassword));
        seed("local-vendor@example.test","Local Vendor One","CRM_VENDOR",passwords.encode(vendorPassword));
        seed("local-vendor-two@example.test","Local Vendor Two","CRM_VENDOR",passwords.encode(vendorPassword));
    }
    private void seed(String email,String name,String role,String hash){var now=clock.instant();UUID id=UUID.nameUUIDFromBytes(("KOC_LOCAL_ONLY:"+email).getBytes(java.nio.charset.StandardCharsets.UTF_8));users.put(id,IdentityUser.restore(id,email,name,hash,role,true,now,now));}
    private static void validatePassword(String password){if(password==null || password.isBlank() || password.length()<12 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)throw new IllegalArgumentException("Supply local-only passwords of at least 12 characters and at most 72 UTF-8 bytes. No default password is provided.");}
    private static IdentityUser copy(IdentityUser user){return IdentityUser.restore(user.getId(),user.getEmail(),user.getDisplayName(),user.getPasswordHash(),role(user),user.isEnabled(),user.getCreatedAt(),user.getUpdatedAt());}
    private static String role(IdentityUser user){if(user.getRoles().size()!=1)throw new IllegalArgumentException("One role required.");return user.getRoles().iterator().next().getCode();}
    public synchronized Optional<IdentityUser> findByNormalizedEmail(String email){return users.values().stream().filter(u->u.getEmail().equals(email)).findFirst().map(LocalIdentityStore::copy);}
    public synchronized Optional<IdentityUser> findById(UUID id){return Optional.ofNullable(users.get(id)).map(LocalIdentityStore::copy);}
    public synchronized long count(){return users.size();}
    public synchronized Page<IdentityUser> findAssignableVendors(Pageable page){var list=users.values().stream().filter(u->role(u).equals("CRM_VENDOR")&&u.isEnabled()&&u.getDisplayName()!=null&&!u.getDisplayName().isBlank()).sorted(Comparator.comparing(u->u.getId().toString())).map(LocalIdentityStore::copy).toList();return new PageImpl<>(list.stream().skip(page.getOffset()).limit(page.getPageSize()).toList(),page,list.size());}
    public synchronized VendorPageResponse accounts(String role,int page,int size){if(page<0 || page>5000 || size<1 || size>100)throw new VendorDirectoryException(org.springframework.http.HttpStatus.BAD_REQUEST,"INVALID_PAGINATION","Invalid bounded pagination.");
        var items=users.values().stream().filter(u->role(u).equals(role)).sorted(Comparator.comparing(u->u.getId().toString())).map(LocalIdentityStore::response).toList();return new VendorPageResponse(items.stream().skip((long)page*size).limit(size).toList(),page,size,items.size(),(items.size()+size-1)/size);}
    public synchronized VendorResponse account(UUID id,String role){var user=users.get(id);if(user==null || !role(user).equals(role))throw new VendorDirectoryException(org.springframework.http.HttpStatus.NOT_FOUND,"ACCOUNT_NOT_FOUND","Local account was not found.");return response(user);}
    private static VendorResponse response(IdentityUser user){return new VendorResponse(user.getId(),user.getDisplayName(),user.isEnabled(),List.of(role(user)));}
    public synchronized IdentityUser save(IdentityUser user){var old=users.get(user.getId());if(old==null || !old.getUpdatedAt().equals(user.getUpdatedAt()))throw new IdentityPersistenceException("STALE_ACCOUNT");
        if(users.values().stream().anyMatch(u->!u.getId().equals(user.getId())&&u.getEmail().equals(user.getEmail())))throw new IdentityPersistenceException("DUPLICATE_EMAIL");
        if(!user.isEnabled() || !user.getPasswordHash().equals(old.getPasswordHash()) || !user.getEmail().equals(old.getEmail()) || !role(user).equals(role(old)))revokeUser(user.getId());
        Instant updated=clock.instant();if(!updated.isAfter(old.getUpdatedAt()))updated=old.getUpdatedAt().plusNanos(1000);
        var saved=IdentityUser.restore(user.getId(),user.getEmail(),user.getDisplayName(),user.getPasswordHash(),role(user),user.isEnabled(),user.getCreatedAt(),updated);users.put(saved.getId(),saved);return copy(saved);}
    public synchronized void createUnique(IdentityUser user){if(!Set.of("CRM_ADMIN","CRM_VENDOR").contains(role(user)) || !user.getEmail().equals(user.getEmail().strip().toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Invalid local account.");
        if(users.containsKey(user.getId()) || users.values().stream().anyMatch(u->u.getEmail().equals(user.getEmail())))throw new IdentityPersistenceException("DUPLICATE_EMAIL");users.put(user.getId(),copy(user));}
    public synchronized void createFirstAdministrator(IdentityUser user){if(!users.isEmpty())throw new IdentityPersistenceException("BOOTSTRAP_NOT_EMPTY");createUnique(user);}
    public void createVendorInvitation(IdentityUser user,RefreshTokenSession session){throw IdentityPersistenceException.gated();}
    public boolean completeVendorPasswordSetup(String hash,String proof,String password){throw IdentityPersistenceException.gated();}
    public synchronized void createForAuthenticatedUser(IdentityUser expected,RefreshTokenSession session){var user=users.get(expected.getId());
        if(user==null || !user.isEnabled() || !user.getUpdatedAt().equals(expected.getUpdatedAt()) || !user.getPasswordHash().equals(expected.getPasswordHash()) || !role(user).equals(role(expected)))throw new IdentityPersistenceException("LOCAL_ACCOUNT_CHANGED");
        if(sessions.containsKey(session.getTokenHash()) || sessions.values().stream().anyMatch(s->s.id.equals(session.getId())))throw new IdentityPersistenceException("LOCAL_SESSION_COLLISION");
        sessions.put(session.getTokenHash(),new Session(session.getId(),user.getId(),session.getTokenFamilyId(),session.getTokenHash(),session.getCsrfTokenHash(),session.getCreatedAt(),session.getExpiresAt()));}
    public synchronized Rotation rotate(String hash,String csrf,UUID nextId,String nextHash,String nextCsrf,Instant now,Instant expires){var old=sessions.get(hash);
        if(old==null)return new Rotation(Status.INVALID,null);
        if(!old.csrf.equals(csrf))return new Rotation(Status.CSRF_REJECTED,null);
        if(old.used!=null){revokeFamily(old.family,now);return new Rotation(Status.REUSED,null);}
        var user=users.get(old.user);
        if(old.revoked!=null || !old.expires.isAfter(now) || user==null || !user.isEnabled()){revokeFamily(old.family,now);return new Rotation(Status.INVALID,null);}
        if(sessions.containsKey(nextHash) || sessions.values().stream().anyMatch(s->s.id.equals(nextId)))throw new IdentityPersistenceException("LOCAL_SESSION_COLLISION");
        old.used=now;old.revoked=now;sessions.put(nextHash,new Session(nextId,old.user,old.family,nextHash,nextCsrf,now,expires));return new Rotation(Status.SUCCESS,copy(user));}
    public synchronized void revoke(String hash,String csrf,Instant now){var session=sessions.get(hash);if(session==null)return;if(!session.csrf.equals(csrf))throw new CsrfValidationException();revokeFamily(session.family,now);}
    private void revokeFamily(UUID family,Instant now){sessions.values().stream().filter(s->s.family.equals(family)&&s.revoked==null).forEach(s->s.revoked=now);}
    private void revokeUser(UUID user){sessions.values().stream().filter(s->s.user.equals(user)&&s.revoked==null).forEach(s->s.revoked=clock.instant());}
}
