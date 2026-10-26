package com.kingofcards.identity.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.kingofcards.identity.entity.IdentityRole;
import com.kingofcards.identity.entity.IdentityUser;
import com.kingofcards.identity.repository.IdentityRoleRepository;
import com.kingofcards.identity.repository.IdentityUserRepository;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapServiceTest {
    @Mock IdentityUserRepository users;
    @Mock IdentityRoleRepository roles;
    @Mock PasswordEncoder encoder;

    @Test
    void createsOnlyTheFirstAdminWithHashedPassword() {
        IdentityRole adminRole = new IdentityRole("CRM_ADMIN", "CRM Administrator");
        when(roles.findByCodeForUpdate("CRM_ADMIN")).thenReturn(Optional.of(adminRole));
        when(users.count()).thenReturn(0L);
        when(users.findByNormalizedEmail("admin@example.com")).thenReturn(Optional.empty());
        when(encoder.encode("a-long-synthetic-password")).thenReturn("bcrypt-hash");

        new AdminBootstrapService(users, roles, encoder)
            .createFirstAdministrator("Admin@Example.com", "a-long-synthetic-password");

        ArgumentCaptor<IdentityUser> saved = ArgumentCaptor.forClass(IdentityUser.class);
        verify(users).save(saved.capture());
        assertEquals("admin@example.com", saved.getValue().getNormalizedEmail());
        assertEquals("bcrypt-hash", saved.getValue().getPasswordHash());
        assertTrue(saved.getValue().isEnabled());
        assertTrue(saved.getValue().getRoles().stream().anyMatch(role -> role.getCode().equals("CRM_ADMIN")));
    }

    @Test
    void refusesBootstrapWhenAnyUserAlreadyExists() {
        when(roles.findByCodeForUpdate("CRM_ADMIN"))
            .thenReturn(Optional.of(new IdentityRole("CRM_ADMIN", "CRM Administrator")));
        when(users.count()).thenReturn(1L);

        assertThrows(IllegalStateException.class, () -> new AdminBootstrapService(users, roles, encoder)
            .createFirstAdministrator("admin@example.com", "a-long-synthetic-password"));
        verify(users, never()).save(any());
        verifyNoInteractions(encoder);
    }
}