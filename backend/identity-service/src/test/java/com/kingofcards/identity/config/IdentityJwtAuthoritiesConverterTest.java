package com.kingofcards.identity.config;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class IdentityJwtAuthoritiesConverterTest {
    private final IdentityJwtAuthoritiesConverter converter = new IdentityJwtAuthoritiesConverter();

    @Test
    void preservesScopeAuthoritiesAndAddsOnlyAllowlistedRoles() {
        var jwt = Jwt.withTokenValue("synthetic").header("alg", "RS256").subject("synthetic-subject")
            .claim("scope", "profile read").claim("roles", List.of("CRM_ADMIN", "CRM_VENDOR", "CRM_VENDOR", "ROOT", "ROLE_CRM_ADMIN", 7))
            .build();
        assertThat(converter.convert(jwt)).extracting(GrantedAuthority::getAuthority)
            .containsExactly("SCOPE_profile", "SCOPE_read");
    }

    @Test
    void scalarRolesAndRoleLookingScopesDoNotGrantAdminAuthority() {
        var jwt = Jwt.withTokenValue("synthetic").header("alg", "RS256")
            .claim("scope", "CRM_ADMIN").claim("roles", "CRM_ADMIN").build();
        assertThat(converter.convert(jwt)).extracting(GrantedAuthority::getAuthority)
            .containsExactly("SCOPE_CRM_ADMIN");
    }

    @Test
    void absentRolesPreserveExistingScopeBehavior() {
        var jwt = Jwt.withTokenValue("synthetic").header("alg", "RS256").claim("scp", List.of("read")).build();
        assertThat(converter.convert(jwt)).extracting(GrantedAuthority::getAuthority).containsExactly("SCOPE_read");
    }

    @Test void acceptsExactlyOneCurrentRoleAndRejectsLegacyRoles() {
        for (String role : List.of("CRM_ADMIN", "CRM_VENDOR")) {
            var jwt=Jwt.withTokenValue("synthetic").header("alg","RS256").claim("roles",List.of(role)).build();
            assertThat(converter.convert(jwt)).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_"+role);
        }
        for (String role : List.of("VENDOR", "CRM_MANAGER", "CRM_AGENT", "ROOT")) {
            var jwt=Jwt.withTokenValue("synthetic").header("alg","RS256").claim("roles",List.of(role)).build();
            assertThat(converter.convert(jwt)).isEmpty();
        }
    }
}
