package com.kingofcards.identity.local;
import java.time.*;
import java.util.*;
import com.kingofcards.identity.entity.RefreshTokenSession;
import com.kingofcards.identity.repository.RefreshTokenSessionRepository.Status;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.assertj.core.api.Assertions.*;

class LocalIdentityStoreTest {
    final Instant now=Instant.parse("2026-11-02T00:00:00Z");
    final BCryptPasswordEncoder bcrypt=new BCryptPasswordEncoder(4);
    final LocalIdentityStore store=new LocalIdentityStore(bcrypt,Clock.fixed(now,ZoneOffset.UTC),"offline-admin-fixture","offline-vendor-fixture");
    @Test void passwordsAreReallyBcryptAndAccountsAreOnlyLocal(){
        var admin=store.findByNormalizedEmail("local-admin@example.test").orElseThrow();
        assertThat(bcrypt.matches("offline-admin-fixture",admin.getPasswordHash())).isTrue();assertThat(bcrypt.matches("wrong",admin.getPasswordHash())).isFalse();
        assertThat(store.count()).isEqualTo(3);assertThat(store.findByNormalizedEmail("rahul.b@kingofcards.com")).isEmpty();assertThat(store.accounts("CRM_VENDOR",0,20).items()).hasSize(2);
    }
    @Test void rotationReplayRevokesEntireFamily(){
        var user=store.findByNormalizedEmail("local-vendor@example.test").orElseThrow();var session=new RefreshTokenSession(user.getId(),UUID.randomUUID(),"hash1","csrf1",now,now.plusSeconds(3600));store.createForAuthenticatedUser(user,session);
        assertThat(store.rotate("hash1","wrong",UUID.randomUUID(),"hash2","csrf2",now,now.plusSeconds(3600)).status()).isEqualTo(Status.CSRF_REJECTED);
        assertThat(store.rotate("hash1","csrf1",UUID.randomUUID(),"hash2","csrf2",now,now.plusSeconds(3600)).status()).isEqualTo(Status.SUCCESS);
        assertThat(store.rotate("hash1","csrf1",UUID.randomUUID(),"hash3","csrf3",now,now.plusSeconds(3600)).status()).isEqualTo(Status.REUSED);
        assertThat(store.rotate("hash2","csrf2",UUID.randomUUID(),"hash4","csrf4",now,now.plusSeconds(3600)).status()).isEqualTo(Status.INVALID);
    }
    @Test void logoutAndDisabledAccountInvalidateLocalSessions(){
        var user=store.findByNormalizedEmail("local-vendor@example.test").orElseThrow();store.createForAuthenticatedUser(user,new RefreshTokenSession(user.getId(),UUID.randomUUID(),"hash1","csrf1",now,now.plusSeconds(3600)));
        store.revoke("hash1","csrf1",now);assertThat(store.rotate("hash1","csrf1",UUID.randomUUID(),"hash2","csrf2",now,now.plusSeconds(3600)).status()).isEqualTo(Status.INVALID);
        assertThatThrownBy(()->store.createUnique(user)).isInstanceOf(com.kingofcards.identity.exception.IdentityPersistenceException.class);
        store.createForAuthenticatedUser(user,new RefreshTokenSession(user.getId(),UUID.randomUUID(),"hash3","csrf3",now,now.plusSeconds(3600)));
        store.save(com.kingofcards.identity.entity.IdentityUser.restore(user.getId(),user.getEmail(),user.getDisplayName(),user.getPasswordHash(),"CRM_VENDOR",false,user.getCreatedAt(),user.getUpdatedAt()));
        assertThat(store.rotate("hash3","csrf3",UUID.randomUUID(),"hash4","csrf4",now,now.plusSeconds(3600)).status()).isEqualTo(Status.INVALID);
        assertThatThrownBy(()->store.createForAuthenticatedUser(user,new RefreshTokenSession(user.getId(),UUID.randomUUID(),"hash5","csrf5",now,now.plusSeconds(3600)))).isInstanceOf(com.kingofcards.identity.exception.IdentityPersistenceException.class);
    }
    @Test void noDefaultOrShortPasswords(){assertThatThrownBy(()->new LocalIdentityStore(bcrypt,Clock.systemUTC(),null,"short")).isInstanceOf(IllegalArgumentException.class);}
}
