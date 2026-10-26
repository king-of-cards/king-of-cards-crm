package com.kingofcards.crm.config;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

public class JwtRoleAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
    private static final Set<String> ALLOWED_ROLES = Set.of("CRM_ADMIN", "CRM_MANAGER", "CRM_AGENT", "VENDOR");
    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null) return List.of();
        return roles.stream()
            .filter(ALLOWED_ROLES::contains)
            .distinct()
            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
            .map(GrantedAuthority.class::cast)
            .toList();
    }
}