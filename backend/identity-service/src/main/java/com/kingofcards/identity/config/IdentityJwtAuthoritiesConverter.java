package com.kingofcards.identity.config;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/** Preserve standard scope authorities and add only known roles from the verified JWT. */
public final class IdentityJwtAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
    private static final Set<String> ROLES = com.kingofcards.identity.entity.IdentityRole.SUPPORTED_CODES;
    private final JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        var authorities = new LinkedHashSet<GrantedAuthority>();
        var scopeAuthorities = scopes.convert(jwt);
        if (scopeAuthorities != null) authorities.addAll(scopeAuthorities);
        Object roles = jwt.getClaims().get("roles");
        if (roles instanceof Collection<?> values && values.size() == 1) {
            for (Object value : values) {
                if (value instanceof String role && ROLES.contains(role)) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
                }
            }
        }
        return authorities;
    }
}
