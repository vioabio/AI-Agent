package com.vio.vioaiagent.config;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Bridges verified browser OIDC role claims to the ROLE_* authorities used by
 * the existing policy intersection. Ordinary OAuth2 attributes, scopes and
 * arbitrary incoming ROLE_* authorities cannot grant application roles.
 */
public final class OidcRoleAuthoritiesMapper implements GrantedAuthoritiesMapper {
    private final String roleClaim;

    public OidcRoleAuthoritiesMapper(String roleClaim) {
        this.roleClaim = roleClaim;
    }

    @Override
    public Collection<? extends GrantedAuthority> mapAuthorities(
            Collection<? extends GrantedAuthority> authorities) {
        Set<GrantedAuthority> mapped = new LinkedHashSet<>();
        for (GrantedAuthority authority : authorities) {
            if (authority instanceof OidcUserAuthority oidc) {
                mapped.add(authority);
                // Use Spring's own effective claims (ID token takes precedence
                // over UserInfo), matching DefaultOidcUser. Spring's
                // OidcUserService validates the UserInfo subject against the ID
                // token before this mapper sees the authority.
                addRoles(mapped, oidc.getAttributes().get(roleClaim));
            } else if (authority.getAuthority().startsWith("SCOPE_")) {
                mapped.add(authority);
            }
        }
        return Set.copyOf(mapped);
    }

    private void addRoles(Set<GrantedAuthority> authorities, Object value) {
        if (value instanceof Collection<?> roles) {
            for (Object role : roles) addRoles(authorities, role);
        } else if (value != null && value.getClass().isArray()) {
            for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++) {
                addRoles(authorities, java.lang.reflect.Array.get(value, i));
            }
        } else if (value instanceof String roles) {
            for (String role : roles.split("[,\\s]+")) {
                String normalized = role.trim();
                if (normalized.regionMatches(true, 0, "ROLE_", 0, 5)) {
                    normalized = normalized.substring(5);
                }
                if (normalized.regionMatches(true, 0, "SCOPE_", 0, 6)) continue;
                if (normalized.matches("^[A-Za-z][A-Za-z0-9._:-]{0,63}$")) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + normalized.toLowerCase(Locale.ROOT)));
                }
            }
        }
    }
}
