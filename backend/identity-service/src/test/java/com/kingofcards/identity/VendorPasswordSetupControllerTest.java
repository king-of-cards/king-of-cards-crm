package com.kingofcards.identity;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import com.kingofcards.identity.config.*;
import com.kingofcards.identity.controller.VendorPasswordSetupController;
import com.kingofcards.identity.entity.IdentityUser;
import com.kingofcards.identity.exception.*;
import com.kingofcards.identity.repository.IdentityUserRepository;
import com.kingofcards.identity.service.VendorPasswordSetupService;

@WebMvcTest(VendorPasswordSetupController.class)
@org.springframework.test.context.ActiveProfiles("legacy-vendor-setup")
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Import({SecurityConfig.class, com.kingofcards.identity.config.TestAccountStatusConfiguration.class,VendorPasswordSetupService.class,VendorPasswordSetupControllerTest.Beans.class})
class VendorPasswordSetupControllerTest {
    static final String INVITE="{\"email\":\"vendor@example.test\",\"displayName\":\"Vendor One\",\"role\":\"CRM_ADMIN\"}";
    static final String SETUP="{\"setupToken\":\""+"a".repeat(43)+"\",\"password\":\"synthetic-password\"}";
    @Autowired MockMvc mvc; @Autowired IdentityUserRepository users; @Autowired JwtDecoder decoder;
    @BeforeEach void resetMocks(){
        reset(users,decoder);
        for(String role:List.of("CRM_ADMIN","CRM_VENDOR","CRM_AGENT","CRM_MANAGER"))
            when(decoder.decode(role)).thenReturn(Jwt.withTokenValue(role).header("alg","RS256")
                .subject(UUID.randomUUID().toString()).claim("roles",List.of(role)).build());
        when(decoder.decode("invalid")).thenThrow(new BadJwtException("invalid"));
    }
    @Test void adminReceivesOneTimeSecretOnlyAfterAtomicCreationAndCannotEscalateRole() throws Exception {
        mvc.perform(post("/admin/vendors/invitations").header("Authorization","Bearer CRM_ADMIN")
            .contentType("application/json").content(INVITE)).andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.setupToken").isString()).andExpect(jsonPath("$.expiresAt").exists())
            .andExpect(jsonPath("$.passwordHash").doesNotExist());
        var user=ArgumentCaptor.forClass(IdentityUser.class);verify(users).createVendorInvitation(user.capture(),any());
        assertEquals("CRM_VENDOR",user.getValue().getRoles().iterator().next().getCode());
    }
    @ParameterizedTest @ValueSource(strings={"CRM_VENDOR","CRM_AGENT","CRM_MANAGER"})
    void nonAdminsCannotInvite(String role) throws Exception {
        mvc.perform(post("/admin/vendors/invitations").header("Authorization","Bearer "+role)
            .contentType("application/json").content(INVITE)).andExpect(status().isForbidden());verifyNoInteractions(users);
    }
    @Test void missingAndInvalidJwtCannotInvite() throws Exception {
        mvc.perform(post("/admin/vendors/invitations").contentType("application/json").content(INVITE)).andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/vendors/invitations").header("Authorization","Bearer invalid")
            .contentType("application/json").content(INVITE)).andExpect(status().isUnauthorized());verifyNoInteractions(users);
    }
    @Test void duplicateEmailDoesNotExposeAnInvitation() throws Exception {
        doThrow(new VendorDirectoryException(HttpStatus.CONFLICT,"DUPLICATE_EMAIL","Account exists."))
            .when(users).createVendorInvitation(any(),any());
        mvc.perform(post("/admin/vendors/invitations").header("Authorization","Bearer CRM_ADMIN")
            .contentType("application/json").content(INVITE)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.setupToken").doesNotExist());
    }
    @Test void possessionSetupNeedsNoJwtButConsumesTheCredentialAtomically() throws Exception {
        when(users.completeVendorPasswordSetup(anyString(),anyString(),anyString())).thenReturn(true,false);
        mvc.perform(post("/auth/vendor-password-setup").contentType("application/json").content(SETUP))
            .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(post("/auth/vendor-password-setup").contentType("application/json").content(SETUP))
            .andExpect(status().isUnauthorized());
        verify(users,times(2)).completeVendorPasswordSetup(anyString(),anyString(),anyString());
    }
    @Test void validationAndOriginAreCheckedBeforePersistence() throws Exception {
        mvc.perform(post("/auth/vendor-password-setup").contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/auth/vendor-password-setup").header("Origin","https://unapproved.example")
            .contentType("application/json").content(SETUP)).andExpect(status().isForbidden());verifyNoInteractions(users);
    }
    @Test void unverifiedOrAmbiguousPersistenceNeverReturnsSuccess() throws Exception {
        when(users.completeVendorPasswordSetup(anyString(),anyString(),anyString())).thenThrow(IdentityPersistenceException.gated());
        mvc.perform(post("/auth/vendor-password-setup").contentType("application/json").content(SETUP))
            .andExpect(status().isServiceUnavailable());
        verify(users,times(1)).completeVendorPasswordSetup(anyString(),anyString(),anyString());
    }
    @TestConfiguration static class Beans {
        @Bean IdentityUserRepository users(){return mock(IdentityUserRepository.class);}
        @Bean JwtDecoder decoder(){return mock(JwtDecoder.class);}
        @Bean PasswordEncoder passwords(){return new BCryptPasswordEncoder(4);}
        @Bean Clock clock(){return Clock.systemUTC();}
        @Bean AuthCookieSettings cookies(){return new AuthCookieSettings(false,"Lax","http://localhost:5173");}
    }
}
