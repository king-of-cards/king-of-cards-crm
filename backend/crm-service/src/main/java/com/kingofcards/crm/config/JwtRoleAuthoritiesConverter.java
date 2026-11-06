package com.kingofcards.crm.config;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

public class JwtRoleAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
    private static final Set<String> ALLOWED_ROLES = Set.of("CRM_ADMIN", "CRM_VENDOR");
    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Object claim=jwt.getClaims().get("roles");
        if (!(claim instanceof Collection<?> roles) || roles.size()!=1) return List.of();
        Object value=roles.iterator().next();
        if (!(value instanceof String role) || !ALLOWED_ROLES.contains(role)) return List.of();
        return List.of(new SimpleGrantedAuthority("ROLE_"+role));
    }
}
