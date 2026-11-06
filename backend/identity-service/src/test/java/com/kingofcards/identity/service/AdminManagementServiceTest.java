package com.kingofcards.identity.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import com.kingofcards.identity.exception.*;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class AdminManagementServiceTest {
    final AdminMutationStore store=mock(AdminMutationStore.class);
    final Instant now=Instant.parse("2026-11-01T00:00:00Z");
    final AdminManagementService service=new AdminManagementService(store,Clock.fixed(now,ZoneOffset.UTC));
    final UUID actor=UUID.randomUUID(),other=UUID.randomUUID();
    JwtAuthenticationToken auth(String role){return new JwtAuthenticationToken(Jwt.withTokenValue("synthetic").header("alg","RS256").subject(actor.toString()).build(),List.of(new SimpleGrantedAuthority("ROLE_"+role)));}
    @Test void simulatedInvitationUsesNormalizedEmailAndDomainSeparatedHashesNotPasswords() {
        when(store.invite(eq(actor),eq("admin@example.test"),eq("New Admin"),anyString(),anyString(),any())).thenReturn(other);
        var result=service.invite(" Admin@Example.test "," New Admin ",auth("CRM_ADMIN"));
        assertEquals(other,result.subjectId());assertTrue(result.setupToken().matches("[A-Za-z0-9_-]{43}"));
        assertEquals(now.plusSeconds(1800),result.expiresAt());assertFalse(result.toString().contains(result.setupToken()));
        var hashes=org.mockito.ArgumentCaptor.forClass(String.class);var proofs=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(store).invite(eq(actor),eq("admin@example.test"),eq("New Admin"),hashes.capture(),proofs.capture(),eq(result.expiresAt()));
        assertTrue(hashes.getValue().matches("[0-9a-f]{64}"));assertNotEquals(hashes.getValue(),proofs.getValue());
    }
    @Test void duplicateEmailAndUnknownCommitDoNotReturnInvitationOrReplay() {
        doThrow(new IdentityPersistenceException("DUPLICATE_EMAIL")).when(store).invite(any(),anyString(),anyString(),anyString(),anyString(),any());
        assertEquals(org.springframework.http.HttpStatus.CONFLICT,assertThrows(VendorDirectoryException.class,()->service.invite("admin@example.test","Admin",auth("CRM_ADMIN"))).status());
        verify(store,times(1)).invite(any(),anyString(),anyString(),anyString(),anyString(),any());
    }
    @Test void cannotBlockSelfOrOperateAsVendor() {
        assertThrows(AccessDeniedException.class,()->service.setEnabled(actor,false,auth("CRM_ADMIN")));
        assertThrows(AccessDeniedException.class,()->service.setEnabled(other,false,auth("CRM_VENDOR")));
        verifyNoInteractions(store);
    }
    @Test void transactionRejectionIsPropagatedWithoutUnsafeCountThenSave() {
        doThrow(new IdentityPersistenceException("LAST_ACTIVE_ADMIN")).when(store).setEnabled(actor,other,false);
        assertEquals(org.springframework.http.HttpStatus.CONFLICT,assertThrows(VendorDirectoryException.class,()->service.setEnabled(other,false,auth("CRM_ADMIN"))).status());
        service.setEnabled(other,true,auth("CRM_ADMIN"));
        verify(store).setEnabled(actor,other,false);verify(store).setEnabled(actor,other,true);
    }
    @Test void realDefaultAdapterCannotMutateAccountsOrSetupCredentials() {
        var blocked=new UnverifiedAdminMutationStore();
        assertEquals("ADMIN_MUTATIONS_UNVERIFIED",assertThrows(IdentityPersistenceException.class,()->blocked.invite(actor,"x@example.test","X","hash","proof",now)).code());
        assertThrows(IdentityPersistenceException.class,()->blocked.setEnabled(actor,other,false));
        assertThrows(IdentityPersistenceException.class,()->blocked.completeSetup("hash","proof","bcrypt"));
    }
}
