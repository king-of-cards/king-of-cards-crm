package com.kingofcards.crm.config;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class JwtRoleAuthoritiesConverterTest {
    private final JwtRoleAuthoritiesConverter converter=new JwtRoleAuthoritiesConverter();
    @Test void acceptsOnlyOneApprovedRole() {
        for (String role: List.of("CRM_ADMIN","CRM_VENDOR")) {
            assertThat(converter.convert(jwt(List.of(role)))).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_"+role);
        }
    }
    @Test void rejectsLegacyUnknownMixedAndMalformedRoles() {
        for (Object roles: List.of(List.of("VENDOR"),List.of("CRM_MANAGER"),List.of("CRM_AGENT"),
                List.of("CRM_ADMIN","CRM_VENDOR"),List.of("CRM_ADMIN","ROOT"),List.of(7),"CRM_ADMIN",List.of())) {
            assertThat(converter.convert(jwt(roles))).isEmpty();
        }
    }
    private Jwt jwt(Object roles) {
        return Jwt.withTokenValue("synthetic").header("alg","RS256").claim("roles",roles).build();
    }
}
