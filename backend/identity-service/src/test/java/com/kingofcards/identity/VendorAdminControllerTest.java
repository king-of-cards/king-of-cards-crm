package com.kingofcards.identity;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.aMapWithSize;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import com.kingofcards.identity.config.SecurityConfig;
import com.kingofcards.identity.controller.VendorAdminController;
import com.kingofcards.identity.entity.IdentityRole;
import com.kingofcards.identity.entity.IdentityUser;
import com.kingofcards.identity.exception.VendorDirectoryExceptionHandler;
import com.kingofcards.identity.repository.IdentityUserRepository;
import com.kingofcards.identity.service.VendorDirectoryService;

@WebMvcTest(VendorAdminController.class)
@Import({SecurityConfig.class, com.kingofcards.identity.config.TestAccountStatusConfiguration.class, VendorDirectoryService.class, VendorDirectoryExceptionHandler.class,
    VendorAdminControllerTest.Beans.class})
class VendorAdminControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JwtDecoder decoder;
    @Autowired IdentityUserRepository users;
    private IdentityUser vendor;

    @BeforeEach
    void setup() {
        reset(decoder, users);
        vendor = new IdentityUser("vendor@example.test", "vendor@example.test", "synthetic-hash", true);
        vendor.addRole(new IdentityRole("CRM_VENDOR", "Vendor"));
        for (String role : List.of("CRM_ADMIN", "CRM_VENDOR", "CRM_MANAGER", "CRM_AGENT")) {
            when(decoder.decode(role)).thenReturn(Jwt.withTokenValue(role).header("alg", "RS256")
                .subject(UUID.randomUUID().toString()).claim("roles", List.of(role)).build());
        }
        when(decoder.decode("no-role")).thenReturn(Jwt.withTokenValue("no-role").header("alg", "RS256")
            .subject(UUID.randomUUID().toString()).claim("scope", "CRM_ADMIN").build());
        when(decoder.decode("invalid")).thenThrow(new BadJwtException("synthetic invalid JWT"));
    }

    private String path() { return "/admin/vendors/" + vendor.getId(); }

    @Test
    void adminCanListWithSafeFieldsAndDefaultPage() throws Exception {
        vendor.setDisplayName("Vendor One");
        when(users.findAssignableVendors(any())).thenAnswer(call -> new PageImpl<>(List.of(vendor), call.getArgument(0), 1));
        mvc.perform(get("/admin/vendors").header("Authorization", "Bearer CRM_ADMIN"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.items[0]").value(aMapWithSize(4)))
            .andExpect(jsonPath("$.items[0].subjectId").value(vendor.getId().toString()))
            .andExpect(jsonPath("$.items[0].displayName").value("Vendor One"))
            .andExpect(jsonPath("$.items[0].enabled").value(true))
            .andExpect(jsonPath("$.items[0].roles[0]").value("CRM_VENDOR"));
    }

    @Test
    void adminCanRetrieveOnlyTheExplicitDirectoryFields() throws Exception {
        vendor.setDisplayName("Vendor One");
        when(users.findById(vendor.getId())).thenReturn(Optional.of(vendor));
        mvc.perform(get(path()).header("Authorization", "Bearer CRM_ADMIN"))
            .andExpect(status().isOk()).andExpect(jsonPath("$").value(aMapWithSize(4)))
            .andExpect(jsonPath("$.subjectId").value(vendor.getId().toString()))
            .andExpect(jsonPath("$.email").doesNotExist()).andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void adminCanProvisionTrimmedNameWithoutChangingRolesOrReturningSecrets() throws Exception {
        when(users.findById(vendor.getId())).thenReturn(Optional.of(vendor));
        when(users.save(vendor)).thenReturn(vendor);
        mvc.perform(patch(path() + "/display-name").header("Authorization", "Bearer CRM_ADMIN")
                .contentType("application/json").content("{\"displayName\":\"  Vendor One  \"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$").value(aMapWithSize(4)))
            .andExpect(jsonPath("$.displayName").value("Vendor One"))
            .andExpect(jsonPath("$.roles[0]").value("CRM_VENDOR"));
        verify(users).save(vendor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CRM_VENDOR", "CRM_MANAGER", "CRM_AGENT", "no-role"})
    void nonAdminsCannotAccessAnyAdminEndpointOrSpoofRoles(String token) throws Exception {
        mvc.perform(get("/admin/vendors").param("role", "CRM_ADMIN").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
        mvc.perform(get(path()).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(patch(path() + "/display-name").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"displayName\":\"Vendor One\",\"roles\":[\"CRM_ADMIN\"]}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(users);
    }

    @Test
    void missingJwtCannotAccessAnyEndpoint() throws Exception {
        mvc.perform(get("/admin/vendors")).andExpect(status().isUnauthorized());
        mvc.perform(get(path())).andExpect(status().isUnauthorized());
        mvc.perform(patch(path() + "/display-name").contentType("application/json")
            .content("{\"displayName\":\"Vendor One\"}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(users);
    }

    @Test
    void invalidJwtCannotAccessAnyEndpoint() throws Exception {
        mvc.perform(get("/admin/vendors").header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
        mvc.perform(get(path()).header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
        mvc.perform(patch(path() + "/display-name").header("Authorization", "Bearer invalid")
                .contentType("application/json").content("{\"displayName\":\"Vendor One\"}"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(users);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "disabled", "non-vendor"})
    void rejectsUnknownDisabledAndNonVendorAccounts(String kind) throws Exception {
        IdentityUser target = vendor;
        if ("disabled".equals(kind)) {
            target = new IdentityUser("disabled@example.test", "disabled@example.test", "synthetic-hash", false);
            target.addRole(new IdentityRole("CRM_VENDOR", "Vendor"));
        } else if ("non-vendor".equals(kind)) {
            target = new IdentityUser("staff@example.test", "staff@example.test", "synthetic-hash", true);
        }
        String targetPath = "/admin/vendors/" + target.getId();
        when(users.findById(target.getId())).thenReturn("unknown".equals(kind) ? Optional.empty() : Optional.of(target));
        mvc.perform(get(targetPath).header("Authorization", "Bearer CRM_ADMIN"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("VENDOR_NOT_FOUND"));
        mvc.perform(patch(targetPath + "/display-name").header("Authorization", "Bearer CRM_ADMIN")
                .contentType("application/json").content("{\"displayName\":\"Vendor One\"}"))
            .andExpect(status().isNotFound());
        verify(users, never()).save(any());
    }

    @Test
    void unprovisionedVendorReturnsClearConflict() throws Exception {
        when(users.findById(vendor.getId())).thenReturn(Optional.of(vendor));
        mvc.perform(get(path()).header("Authorization", "Bearer CRM_ADMIN"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VENDOR_DISPLAY_NAME_REQUIRED"));
        verify(users, never()).save(any());
    }

    static Stream<String> invalidNames() {
        return Stream.of("{}", "{\"displayName\":null}", "{\"displayName\":\"\"}",
            "{\"displayName\":\"   \"}", "{\"displayName\":\"" + "A".repeat(121) + "\"}");
    }

    @ParameterizedTest
    @MethodSource("invalidNames")
    void rejectsInvalidDisplayNamesBeforeAccessingRepository(String json) throws Exception {
        mvc.perform(patch(path() + "/display-name").header("Authorization", "Bearer CRM_ADMIN")
            .contentType("application/json").content(json)).andExpect(status().isBadRequest());
        verifyNoInteractions(users);
    }

    @Test
    void trimsBeforeMaximumLengthValidation() throws Exception {
        when(users.findById(vendor.getId())).thenReturn(Optional.of(vendor));
        when(users.save(vendor)).thenReturn(vendor);
        mvc.perform(patch(path() + "/display-name").header("Authorization", "Bearer CRM_ADMIN")
            .contentType("application/json").content("{\"displayName\":\" " + "A".repeat(120) + " \"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("A".repeat(120)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "page=invalid", "size=invalid"})
    void rejectsInvalidPagination(String setting) throws Exception {
        String[] pair = setting.split("=");
        mvc.perform(get("/admin/vendors").param(pair[0], pair[1]).header("Authorization", "Bearer CRM_ADMIN"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(users);
    }

    @Test
    void malformedSubjectIsBadRequest() throws Exception {
        mvc.perform(get("/admin/vendors/not-a-uuid").header("Authorization", "Bearer CRM_ADMIN"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(users);
    }

    @TestConfiguration
    static class Beans {
        @Bean JwtDecoder jwtDecoder() { return mock(JwtDecoder.class); }
        @Bean IdentityUserRepository identityUserRepository() { return mock(IdentityUserRepository.class); }
    }
}
