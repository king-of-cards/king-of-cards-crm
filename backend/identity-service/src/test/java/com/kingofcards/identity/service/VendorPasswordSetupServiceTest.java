package com.kingofcards.identity.service;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.http.HttpStatus;
import com.kingofcards.identity.entity.*;
import com.kingofcards.identity.repository.IdentityUserRepository;
import com.kingofcards.identity.exception.*;

class VendorPasswordSetupServiceTest {
    @Test void simulatedConcurrentConsumptionReturnsOnlyOneSuccess() throws Exception {
        var consumed=new java.util.concurrent.atomic.AtomicBoolean();
        when(users.completeVendorPasswordSetup(anyString(),anyString(),anyString()))
            .thenAnswer(call->consumed.compareAndSet(false,true));
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var barrier=new java.util.concurrent.CyclicBarrier(2);
            java.util.concurrent.Callable<Boolean> attempt=()->{
                barrier.await(5,java.util.concurrent.TimeUnit.SECONDS);
                try{setup.complete("a".repeat(43),"synthetic-password");return true;}catch(BadCredentialsException denied){return false;}
            };
            var results=pool.invokeAll(java.util.List.of(attempt,attempt));
            assertNotEquals(results.get(0).get(),results.get(1).get());
        }
        verify(users,times(2)).completeVendorPasswordSetup(anyString(),anyString(),anyString());
        // AtomicBoolean models a confirmed result only; it is never used by production code.
    }
    final IdentityUserRepository users=mock(IdentityUserRepository.class);
    final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder(4);
    final VendorPasswordSetupService setup=new VendorPasswordSetupService(users,passwords,Clock.systemUTC());
    @Test void invitationPersistsOnlyDomainSeparatedHashesAndReturnsSecretAfterCommit() throws Exception {
        var response=setup.invite(" Vendor@Example.test "," Vendor One ");
        var user=ArgumentCaptor.forClass(IdentityUser.class);
        var session=ArgumentCaptor.forClass(RefreshTokenSession.class);
        verify(users).createVendorInvitation(user.capture(),session.capture());
        verifyNoMoreInteractions(users);
        assertEquals("vendor@example.test",user.getValue().getEmail());
        assertEquals("Vendor One",user.getValue().getDisplayName());
        assertEquals("CRM_VENDOR",user.getValue().getRoles().iterator().next().getCode());
        assertEquals(user.getValue().getId(),response.subjectId());
        assertEquals(user.getValue().getId(),session.getValue().getUserId());
        assertEquals(Duration.ofMinutes(30),Duration.between(session.getValue().getCreatedAt(),session.getValue().getExpiresAt()));
        assertEquals(VendorPasswordSetupService.tokenHash(response.setupToken()),session.getValue().getTokenHash());
        assertNotEquals(session.getValue().getTokenHash(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(response.setupToken().getBytes(StandardCharsets.UTF_8))));
        assertNotEquals(session.getValue().getTokenHash(),session.getValue().getCsrfTokenHash());
        assertFalse(response.toString().contains(response.setupToken()));
    }
    @Test void duplicateOrUncertainCommitNeverReturnsOrRetriesInvitation() {
        doThrow(new VendorDirectoryException(HttpStatus.CONFLICT,"DUPLICATE_EMAIL","Account already exists."))
            .when(users).createVendorInvitation(any(),any());
        assertThrows(VendorDirectoryException.class,()->setup.invite("vendor@example.test","Vendor"));
        verify(users,times(1)).createVendorInvitation(any(),any());
        reset(users);
        doThrow(IdentityPersistenceException.gated()).when(users).createVendorInvitation(any(),any());
        assertThrows(IdentityPersistenceException.class,()->setup.invite("vendor@example.test","Vendor"));
        verify(users,times(1)).createVendorInvitation(any(),any());
    }
    @Test void onlyConfirmedSetupSucceedsAndReplayOrDisabledAccountFails() {
        String raw="a".repeat(43);
        when(users.completeVendorPasswordSetup(anyString(),anyString(),anyString())).thenReturn(true,false);
        setup.complete(raw,"synthetic-password");
        assertThrows(BadCredentialsException.class,()->setup.complete(raw,"different-password"));
        var hash=ArgumentCaptor.forClass(String.class);
        verify(users,times(2)).completeVendorPasswordSetup(eq(VendorPasswordSetupService.tokenHash(raw)),
            eq(VendorPasswordSetupService.proofHash(raw)),hash.capture());
        assertTrue(passwords.matches("synthetic-password",hash.getAllValues().get(0)));
        reset(users); // false also represents expired, unknown, non-vendor or administratively disabled accounts.
        when(users.completeVendorPasswordSetup(anyString(),anyString(),anyString())).thenReturn(false);
        assertThrows(BadCredentialsException.class,()->setup.complete(raw,"synthetic-password"));
    }
    @Test void validationAndUncertainSetupFailClosed() {
        assertThrows(BadCredentialsException.class,()->setup.complete("invalid","synthetic-password"));
        assertThrows(VendorDirectoryException.class,()->setup.complete("a".repeat(43),"short"));
        assertThrows(VendorDirectoryException.class,()->setup.complete("a".repeat(43),"\u00e9".repeat(37)));
        verifyNoInteractions(users);
        when(users.completeVendorPasswordSetup(anyString(),anyString(),anyString())).thenThrow(IdentityPersistenceException.gated());
        assertThrows(IdentityPersistenceException.class,()->setup.complete("a".repeat(43),"synthetic-password"));
        verify(users,times(1)).completeVendorPasswordSetup(anyString(),anyString(),anyString());
    }
}
