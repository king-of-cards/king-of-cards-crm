package com.kingofcards.identity;

import java.time.Instant;
import java.util.*;
import com.kingofcards.identity.config.SecurityConfig;
import com.kingofcards.identity.controller.VendorSelfProfileController;
import com.kingofcards.identity.entity.IdentityUser;
import com.kingofcards.identity.repository.IdentityUserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(VendorSelfProfileController.class)
@Import({SecurityConfig.class, com.kingofcards.identity.config.TestAccountStatusConfiguration.class, VendorSelfProfileControllerTest.Beans.class})
class VendorSelfProfileControllerTest {
    @Autowired MockMvc mvc; @Autowired JwtDecoder decoder; @Autowired IdentityUserRepository users;
    final UUID subject = UUID.fromString("00000000-0000-4000-8000-000000000001");
    @BeforeEach void setup() {
        reset(users, decoder);
        for (String role : List.of("CRM_VENDOR", "CRM_ADMIN")) when(decoder.decode(role)).thenReturn(Jwt.withTokenValue(role).header("alg", "RS256").subject(subject.toString()).claim("roles", List.of(role)).build());
        when(decoder.decode("invalid")).thenThrow(new BadJwtException("Invalid synthetic token"));
    }
    @Test void vendorCanOnlyResolveOwnVerifiedProfileEvenIfAnotherSubjectIsSupplied() throws Exception {
        when(users.findById(subject)).thenReturn(Optional.of(IdentityUser.restore(subject, "synthetic@example.invalid", "Trusted name", "not-a-real-hash", "CRM_VENDOR", true, Instant.EPOCH, Instant.EPOCH)));
        mvc.perform(get("/auth/vendor-profile").param("vendorId", UUID.randomUUID().toString()).header("Authorization", "Bearer CRM_VENDOR"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.subjectId").value(subject.toString()))
            .andExpect(jsonPath("$.displayName").value("Trusted name")).andExpect(jsonPath("$.email").doesNotExist()).andExpect(jsonPath("$.passwordHash").doesNotExist());
        verify(users).findById(subject); verifyNoMoreInteractions(users);
    }
    @Test void adminAnonymousAndInvalidTokensCannotUseVendorSelfProfile() throws Exception {
        mvc.perform(get("/auth/vendor-profile")).andExpect(status().isUnauthorized());
        mvc.perform(get("/auth/vendor-profile").header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
        mvc.perform(get("/auth/vendor-profile").header("Authorization", "Bearer CRM_ADMIN")).andExpect(status().isForbidden());
        verifyNoInteractions(users);
    }
    @Test void disabledOrMissingAccountCannotResolveProfile() throws Exception {
        when(users.findById(subject)).thenReturn(Optional.empty());
        mvc.perform(get("/auth/vendor-profile").header("Authorization", "Bearer CRM_VENDOR")).andExpect(status().isForbidden());
        when(users.findById(subject)).thenReturn(Optional.of(IdentityUser.restore(subject, "synthetic@example.invalid", "Trusted name", "not-a-real-hash", "CRM_VENDOR", false, Instant.EPOCH, Instant.EPOCH)));
        mvc.perform(get("/auth/vendor-profile").header("Authorization", "Bearer CRM_VENDOR")).andExpect(status().isForbidden());
    }
    @TestConfiguration static class Beans {
        @Bean JwtDecoder decoder() { return mock(JwtDecoder.class); }
        @Bean IdentityUserRepository users() { return mock(IdentityUserRepository.class); }
    }
}
