package com.kingofcards.identity.service;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.kingofcards.identity.config.*;
import com.kingofcards.identity.entity.*;
import com.kingofcards.identity.exception.*;
import com.kingofcards.identity.repository.*;

/** Repository commit results are SIMULATED. This is not evidence of BigQuery concurrency safety. */
class IdentityWorkflowOfflineTest {
    @Test void bootstrapAdminLoginInviteSetupVendorLoginRefreshLogoutAndBlocking() throws Exception {
        var users=mock(IdentityUserRepository.class);
        var sessions=mock(RefreshTokenSessionRepository.class);
        var passwords=new BCryptPasswordEncoder(4);
        var clock=Clock.systemUTC();
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        var pair=generator.generateKeyPair();
        var rsa=new RSAKey.Builder((RSAPublicKey)pair.getPublic()).privateKey(pair.getPrivate()).keyID("offline").build();
        var jwt=new JwtTokenService(new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsa))),
            new JwtSettings("http://localhost:8081","king-of-cards-crm",Duration.ofMinutes(15),"unused"),clock,new JWKSet(rsa.toPublicJWK()));
        var decoder=NimbusJwtDecoder.withPublicKey((RSAPublicKey)pair.getPublic()).build();
        var refresh=new RefreshTokenService(sessions,new RefreshSettings(Duration.ofDays(7)),clock);
        var auth=new AuthenticationService(users,passwords,jwt,refresh);
        var admin=new AtomicReference<IdentityUser>();
        var vendor=new AtomicReference<IdentityUser>();
        doAnswer(call->{admin.set(call.getArgument(0));return null;}).when(users).createFirstAdministrator(any());
        new AdminBootstrapService(users,passwords).createFirstAdministrator("Admin@Example.test","synthetic-admin-password");
        when(users.findByNormalizedEmail(anyString())).thenAnswer(call->{
            String email=call.getArgument(0); return Optional.ofNullable(email.equals("admin@example.test")?admin.get():
                email.equals("vendor@example.test")?vendor.get():null);
        });
        var adminLogin=auth.login(" Admin@Example.test ","synthetic-admin-password");
        assertEquals(List.of("CRM_ADMIN"),decoder.decode(adminLogin.accessToken()).getClaimAsStringList("roles"));
        doAnswer(call->{vendor.set(call.getArgument(0));return null;}).when(users).createVendorInvitation(any(),any());
        var setup=new VendorPasswordSetupService(users,passwords,clock);
        var invitation=setup.invite("Vendor@Example.test","Vendor One");
        assertThrows(BadCredentialsException.class,()->auth.login("vendor@example.test","synthetic-vendor-password"));
        var consumed=new AtomicBoolean();
        when(users.completeVendorPasswordSetup(anyString(),anyString(),anyString())).thenAnswer(call->{
            if(!consumed.compareAndSet(false,true))return false;
            var old=vendor.get(); vendor.set(IdentityUser.restore(old.getId(),old.getEmail(),old.getDisplayName(),call.getArgument(2),
                "CRM_VENDOR",true,old.getCreatedAt(),old.getUpdatedAt().plusNanos(1000)));return true;
        });
        setup.complete(invitation.setupToken(),"synthetic-vendor-password");
        assertThrows(BadCredentialsException.class,()->setup.complete(invitation.setupToken(),"different-password"));
        var vendorLogin=auth.login("vendor@example.test","synthetic-vendor-password");
        assertEquals(List.of("CRM_VENDOR"),decoder.decode(vendorLogin.accessToken()).getClaimAsStringList("roles"));
        assertEquals(invitation.subjectId().toString(),decoder.decode(vendorLogin.accessToken()).getSubject());
        assertThrows(BadCredentialsException.class,()->auth.login("vendor@example.test","wrong-password"));
        assertThrows(BadCredentialsException.class,()->auth.login("unknown@example.test","wrong-password"));
        when(sessions.rotate(anyString(),anyString(),any(),anyString(),anyString(),any(),any()))
            .thenReturn(new RefreshTokenSessionRepository.Rotation(RefreshTokenSessionRepository.Status.SUCCESS,admin.get()),
                new RefreshTokenSessionRepository.Rotation(RefreshTokenSessionRepository.Status.REUSED,null));
        auth.refresh(adminLogin.refreshToken(),adminLogin.csrfToken(),adminLogin.csrfToken());
        assertThrows(RefreshTokenReuseException.class,()->auth.refresh(adminLogin.refreshToken(),adminLogin.csrfToken(),adminLogin.csrfToken()));
        auth.logout(vendorLogin.refreshToken(),vendorLogin.csrfToken(),vendorLogin.csrfToken());
        verify(sessions).revoke(anyString(),anyString(),any());
        when(users.findById(invitation.subjectId())).thenAnswer(call->Optional.of(vendor.get()));
        when(users.save(any())).thenAnswer(call->{vendor.set(call.getArgument(0));return vendor.get();});
        var provisioning=new VendorProvisioningService(users,passwords);
        assertFalse(provisioning.setEnabled(invitation.subjectId(),false).enabled());
        // The mocked save confirms the atomic account-change/session-revocation contract, not real-engine behavior.
        verify(users).save(argThat(value->!value.isEnabled() && value.getId().equals(invitation.subjectId())));
        assertThrows(BadCredentialsException.class,()->auth.login("vendor@example.test","synthetic-vendor-password"));
        assertThrows(BadCredentialsException.class,()->auth.currentUser(invitation.subjectId()));
        assertTrue(provisioning.setEnabled(invitation.subjectId(),true).enabled());
    }
    @Test void simulatedConcurrentBootstrapAdmitsOneWithoutServiceSideCheckThenInsert() throws Exception {
        var users=mock(IdentityUserRepository.class);var committed=new AtomicBoolean();
        doAnswer(call->{if(!committed.compareAndSet(false,true))throw new VendorDirectoryException(HttpStatus.CONFLICT,"BOOTSTRAP_NOT_EMPTY","Store not empty");return null;})
            .when(users).createFirstAdministrator(any());
        var bootstrap=new AdminBootstrapService(users,new BCryptPasswordEncoder(4));
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var barrier=new CyclicBarrier(2);
            Callable<Boolean> attempt=()->{barrier.await(5,TimeUnit.SECONDS);try{bootstrap.createFirstAdministrator("admin@example.test","synthetic-password");return true;}
                catch(VendorDirectoryException expected){return false;}};
            var results=pool.invokeAll(List.of(attempt,attempt));
            assertNotEquals(results.get(0).get(),results.get(1).get());
        }
        verify(users,times(2)).createFirstAdministrator(any());verify(users,never()).count();verify(users,never()).createUnique(any());
    }
}
