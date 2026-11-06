package com.kingofcards.identity.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.kingofcards.identity.exception.*;
import org.junit.jupiter.api.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authentication.BadCredentialsException;

class AdminPasswordSetupServiceTest {
    final AdminMutationStore store=mock(AdminMutationStore.class);
    final PasswordEncoder passwords=spy(new BCryptPasswordEncoder(4));
    final AdminPasswordSetupService service=new AdminPasswordSetupService(store,passwords);
    final String token="a".repeat(43),password="synthetic-long-password";
    @Test void validSimulatedSetupUsesBcryptAndDomainSeparatedHashes() {
        when(store.setupAvailable()).thenReturn(true);service.complete(token,password);
        var hash=org.mockito.ArgumentCaptor.forClass(String.class);var proof=org.mockito.ArgumentCaptor.forClass(String.class);var credential=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(store).completeSetup(hash.capture(),proof.capture(),credential.capture());
        assertNotEquals(token,hash.getValue());assertTrue(hash.getValue().matches("[a-f0-9]{64}"));assertNotEquals(hash.getValue(),proof.getValue());
        assertTrue(passwords.matches(password,credential.getValue()));assertNotEquals(password,credential.getValue());
    }
    @Test void invalidInputsCannotReachPersistence() {
        assertThrows(VendorDirectoryException.class,()->service.complete("bad",password));
        assertThrows(VendorDirectoryException.class,()->service.complete(token,"short"));
        assertThrows(VendorDirectoryException.class,()->service.complete(token,"é".repeat(37)));
        verifyNoInteractions(store,passwords);
    }
    @Test void unverifiedDefaultCannotHashOrMutate() {
        assertEquals("ADMIN_MUTATIONS_UNVERIFIED",assertThrows(IdentityPersistenceException.class,()->service.complete(token,password)).code());
        verify(store).setupAvailable();verifyNoMoreInteractions(store);verifyNoInteractions(passwords);
    }
    @Test void expiredOrReplayedCredentialIsNotRetried() {
        when(store.setupAvailable()).thenReturn(true);doThrow(new BadCredentialsException("Invalid setup credential")).when(store).completeSetup(anyString(),anyString(),anyString());
        assertThrows(BadCredentialsException.class,()->service.complete(token,password));
        verify(store,times(1)).completeSetup(anyString(),anyString(),anyString());
    }
}
