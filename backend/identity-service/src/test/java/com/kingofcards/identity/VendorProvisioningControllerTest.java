package com.kingofcards.identity;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import com.kingofcards.identity.config.SecurityConfig;
import com.kingofcards.identity.controller.VendorProvisioningController;
import com.kingofcards.identity.entity.*;
import com.kingofcards.identity.exception.IdentityPersistenceException;
import com.kingofcards.identity.repository.IdentityUserRepository;
import com.kingofcards.identity.service.VendorProvisioningService;

@WebMvcTest(VendorProvisioningController.class)
@Import({SecurityConfig.class, com.kingofcards.identity.config.TestAccountStatusConfiguration.class, VendorProvisioningService.class, VendorProvisioningControllerTest.Beans.class})
class VendorProvisioningControllerTest {
    @Autowired MockMvc mvc;
    @Autowired IdentityUserRepository users;
    @Autowired JwtDecoder decoder;
    @Autowired PasswordEncoder passwords;
    static final String REQUEST = "{\"email\":\"Vendor@Example.test\",\"password\":\"synthetic-password\",\"displayName\":\" Vendor One \"}";

    @BeforeEach void setup() {
        reset(users, decoder);
        for(String role:List.of("CRM_ADMIN","CRM_VENDOR","CRM_MANAGER","CRM_AGENT"))
            when(decoder.decode(role)).thenReturn(Jwt.withTokenValue(role).header("alg","RS256")
                .subject(UUID.randomUUID().toString()).claim("roles",List.of(role)).build());
        when(decoder.decode("invalid")).thenThrow(new BadJwtException("Invalid synthetic token"));
    }

    @Test void createsVendorWithServerRoleNormalizedEmailAndBcryptOnly() throws Exception {
        mvc.perform(post("/admin/vendors").header("Authorization","Bearer CRM_ADMIN").contentType("application/json").content(REQUEST))
            .andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.displayName").value("Vendor One"))
            .andExpect(jsonPath("$.roles[0]").value("CRM_VENDOR"))
            .andExpect(jsonPath("$.passwordHash").doesNotExist()).andExpect(jsonPath("$.email").doesNotExist());
        var capture=ArgumentCaptor.forClass(IdentityUser.class);
        verify(users).createUnique(capture.capture()); verifyNoMoreInteractions(users);
        assertEquals("vendor@example.test",capture.getValue().getEmail());
        assertTrue(passwords.matches("synthetic-password",capture.getValue().getPasswordHash()));
        assertEquals(1,capture.getValue().getRoles().size());
    }

    @Test void ignoresClientRoleAndStatusRatherThanGrantingAdmin() throws Exception {
        mvc.perform(post("/admin/vendors").header("Authorization","Bearer CRM_ADMIN").contentType("application/json")
            .content(REQUEST.replace("}",",\"role\":\"CRM_ADMIN\",\"enabled\":false}")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.roles[0]").value("CRM_VENDOR"))
            .andExpect(jsonPath("$.enabled").value(true));
    }

    @ParameterizedTest @ValueSource(strings={"CRM_VENDOR","CRM_MANAGER","CRM_AGENT"})
    void nonAdminsCannotCreateOrChangeStatus(String role) throws Exception {
        mvc.perform(post("/admin/vendors").header("Authorization","Bearer "+role).contentType("application/json").content(REQUEST))
            .andExpect(status().isForbidden());
        mvc.perform(patch("/admin/vendors/"+UUID.randomUUID()+"/enabled").header("Authorization","Bearer "+role)
            .contentType("application/json").content("{\"enabled\":false}")).andExpect(status().isForbidden());
        verifyNoInteractions(users);
    }

    @Test void missingAndInvalidJwtAreRejected() throws Exception {
        mvc.perform(post("/admin/vendors").contentType("application/json").content(REQUEST)).andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/vendors").header("Authorization","Bearer invalid").contentType("application/json").content(REQUEST))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(users);
    }

    @Test void persistenceGateOrUnknownCommitCannotReturnCreationSuccessOrRetry() throws Exception {
        doThrow(IdentityPersistenceException.gated()).when(users).createUnique(any());
        mvc.perform(post("/admin/vendors").header("Authorization","Bearer CRM_ADMIN").contentType("application/json").content(REQUEST))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("IDENTITY_MUTATIONS_UNVERIFIED"));
        verify(users,times(1)).createUnique(any()); verifyNoMoreInteractions(users);
    }

    @ParameterizedTest @ValueSource(strings={"", "   ", "éééééééééééééééééééééééééééééééééééééé"})
    void invalidPasswordRejectedBeforePersistence(String password) throws Exception {
        mvc.perform(post("/admin/vendors").header("Authorization","Bearer CRM_ADMIN").contentType("application/json")
            .content(REQUEST.replace("synthetic-password",password))).andExpect(status().isBadRequest());
        verifyNoInteractions(users);
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void statusUpdatePreservesSubjectAndVersionAndUsesAtomicRepositoryContract(boolean enabled) throws Exception {
        var user=new IdentityUser("vendor@example.test","vendor@example.test",passwords.encode("synthetic-password"),true);
        user.addRole(new IdentityRole("CRM_VENDOR","Vendor")); user.setDisplayName("Vendor One");
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(users.save(any())).thenAnswer(call->call.getArgument(0));
        mvc.perform(patch("/admin/vendors/"+user.getId()+"/enabled").header("Authorization","Bearer CRM_ADMIN")
            .contentType("application/json").content("{\"enabled\":"+enabled+"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(enabled));
        var saved=ArgumentCaptor.forClass(IdentityUser.class);verify(users).save(saved.capture());
        assertEquals(user.getId(),saved.getValue().getId());
        assertEquals(user.getUpdatedAt(),saved.getValue().getUpdatedAt());
        assertEquals(user.getPasswordHash(),saved.getValue().getPasswordHash());
    }

    @Test void nonVendorAndUnknownAccountsCannotBeUpdated() throws Exception {
        var user=new IdentityUser("admin@example.test","admin@example.test","not-exposed",true);
        user.addRole(new IdentityRole("CRM_ADMIN","Admin"));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        for(UUID id:List.of(user.getId(),UUID.randomUUID()))
            mvc.perform(patch("/admin/vendors/"+id+"/enabled").header("Authorization","Bearer CRM_ADMIN")
                .contentType("application/json").content("{\"enabled\":false}")).andExpect(status().isNotFound());
        verify(users,never()).save(any());
    }

    @TestConfiguration static class Beans {
        @Bean IdentityUserRepository users(){return mock(IdentityUserRepository.class);}
        @Bean JwtDecoder decoder(){return mock(JwtDecoder.class);}
        @Bean PasswordEncoder passwords(){return new BCryptPasswordEncoder(4);}
    }
}
