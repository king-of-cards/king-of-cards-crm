package com.kingofcards.crm;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.kingofcards.crm.config.SecurityConfig;

@WebMvcTest
@Import({SecurityConfig.class, CrmAuthorizationTest.TestConfigurationBeans.class})
class CrmAuthorizationTest {
    @Autowired MockMvc mvc;
    @Test
    void requiresAuthenticationByDefault() throws Exception {
        mvc.perform(get("/__test/admin")).andExpect(status().isUnauthorized());
    }
    @Test
    void enforcesMethodRoleWithoutApprovingBusinessRoleMatrix() throws Exception {
        mvc.perform(get("/__test/admin").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CRM_AGENT"))))
            .andExpect(status().isForbidden());
        mvc.perform(get("/__test/admin").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CRM_ADMIN"))))
            .andExpect(status().isOk());
    }
    @TestConfiguration
    static class TestConfigurationBeans {
        @Bean JwtDecoder jwtDecoder() {
            return token -> { throw new BadJwtException("MockMvc jwt() supplies test authentication."); };
        }
        @Bean ProbeController probeController() { return new ProbeController(); }
    }
    @RestController
    static class ProbeController {
        @GetMapping("/__test/admin")
        @PreAuthorize("hasRole('CRM_ADMIN')")
        String adminOnly() { return "ok"; }
    }
}