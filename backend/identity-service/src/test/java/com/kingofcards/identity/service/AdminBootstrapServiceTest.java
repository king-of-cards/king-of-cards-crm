package com.kingofcards.identity.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.kingofcards.identity.entity.IdentityUser;
import com.kingofcards.identity.repository.IdentityUserRepository;
import com.kingofcards.identity.exception.IdentityPersistenceException;

class AdminBootstrapServiceTest {
    @Test void namedBootstrapTrimsNameAndStillUsesOnlyAtomicFirstAdminOperation() {
        var users=mock(IdentityUserRepository.class); var encoder=mock(PasswordEncoder.class);
        when(encoder.encode("synthetic-password")).thenReturn("bcrypt-hash");
        new AdminBootstrapService(users,encoder).createFirstAdministrator("admin@example.test","synthetic-password","  Administrator  ");
        var account=ArgumentCaptor.forClass(IdentityUser.class);
        verify(users).createFirstAdministrator(account.capture()); verifyNoMoreInteractions(users);
        assertEquals("Administrator",account.getValue().getDisplayName());
    }
    @Test void namedBootstrapRejectsMissingNameBeforeAnyHashingOrWriting() {
        var users=mock(IdentityUserRepository.class); var encoder=mock(PasswordEncoder.class);
        assertThrows(IllegalArgumentException.class,()->new AdminBootstrapService(users,encoder)
            .createFirstAdministrator("admin@example.test","synthetic-password"," "));
        verifyNoInteractions(users,encoder);
    }
    @Test void rejectsPasswordsOverBcryptUtf8ByteLimitBeforeHashingOrWriting() {
        var users = mock(IdentityUserRepository.class);
        var encoder = mock(PasswordEncoder.class);
        assertThrows(IllegalArgumentException.class, () -> new AdminBootstrapService(users, encoder)
            .createFirstAdministrator("admin@example.test", "é".repeat(37)));
        verifyNoInteractions(users, encoder);
    }
    @Test void delegatesEmptyStoreAndUniqueEmailToOneAtomicOperation() {
        var users = mock(IdentityUserRepository.class);
        var encoder = mock(PasswordEncoder.class);
        when(encoder.encode("synthetic-password")).thenReturn("bcrypt-hash");
        new AdminBootstrapService(users, encoder).createFirstAdministrator(" Admin@Example.com ", "synthetic-password");
        var saved = ArgumentCaptor.forClass(IdentityUser.class);
        verify(users).createFirstAdministrator(saved.capture());
        verifyNoMoreInteractions(users); // no unsafe count or read-then-save
        assertEquals("admin@example.com", saved.getValue().getEmail());
        assertEquals("bcrypt-hash", saved.getValue().getPasswordHash());
        assertEquals("CRM_ADMIN", saved.getValue().getRoles().iterator().next().getCode());
    }
    @Test void propagatesGatedOrAmbiguousCreationWithoutRetryOrFallback() {
        var users = mock(IdentityUserRepository.class);
        var encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("bcrypt-hash");
        doThrow(IdentityPersistenceException.gated()).when(users).createFirstAdministrator(any());
        assertThrows(IdentityPersistenceException.class, () -> new AdminBootstrapService(users, encoder)
            .createFirstAdministrator("admin@example.com", "synthetic-password"));
        verify(users, times(1)).createFirstAdministrator(any());
        verify(users, never()).save(any());
    }
}
