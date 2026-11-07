package com.vio.vioaiagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class OidcRoleAuthoritiesMapperTest {
    private final OidcRoleAuthoritiesMapper mapper = new OidcRoleAuthoritiesMapper("roles");

    @Test
    void verifiedIdTokenRolesAreMappedAndScopesPreserved() {
        Set<String> roles = names(mapper.mapAuthorities(List.of(
                authority(Map.of("sub", "alice", "roles", List.of("Trainer", "ROLE_operator")), null),
                new SimpleGrantedAuthority("SCOPE_openid"))));
        assertEquals(Set.of("OIDC_USER", "SCOPE_openid", "ROLE_trainer", "ROLE_operator"), roles);
    }

    @Test
    void verifiedUserInfoUsesSamePrecedenceAsOidcPrincipal() {
        OidcUserAuthority authority = authority(Map.of("sub", "alice", "roles", List.of("trainer")),
                Map.of("sub", "alice", "roles", List.of("operator")));
        DefaultOidcUser principal = new DefaultOidcUser(List.of(authority),
                authority.getIdToken(), authority.getUserInfo());
        assertEquals(List.of("trainer"), principal.getClaims().get("roles"));
        assertEquals(Set.of("OIDC_USER", "ROLE_trainer"), names(mapper.mapAuthorities(List.of(authority))));
        assertEquals(Set.of("OIDC_USER", "ROLE_trainer"), names(mapper.mapAuthorities(List.of(
                authority(Map.of("sub", "alice"), Map.of("sub", "alice", "roles", List.of("trainer")))))));
    }

    @Test
    void ordinaryOauthAttributesAndIncomingRoleAuthoritiesCannotGrantRoles() {
        assertTrue(mapper.mapAuthorities(List.of(
                new OAuth2UserAuthority(Map.of("sub", "alice", "roles", List.of("operator"))),
                new SimpleGrantedAuthority("ROLE_operator"))).isEmpty());
    }

    @Test
    void missingOrMalformedRolesGrantNoApplicationAuthority() {
        assertEquals(Set.of("OIDC_USER"), names(mapper.mapAuthorities(List.of(
                authority(Map.of("sub", "alice"), null)))));
        assertEquals(Set.of("OIDC_USER"), names(mapper.mapAuthorities(List.of(
                authority(Map.of("sub", "alice", "roles", Map.of("admin", true)), null)))));
        assertEquals(Set.of("OIDC_USER"), names(mapper.mapAuthorities(List.of(
                authority(Map.of("sub", "alice", "roles", List.of("SCOPE_admin", "bad!role", 1)), null)))));
    }

    private OidcUserAuthority authority(Map<String, Object> claims, Map<String, Object> userInfo) {
        return new OidcUserAuthority(new OidcIdToken("fixture", Instant.now(),
                Instant.now().plusSeconds(300), claims), userInfo == null ? null : new OidcUserInfo(userInfo));
    }

    private Set<String> names(Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }
}
