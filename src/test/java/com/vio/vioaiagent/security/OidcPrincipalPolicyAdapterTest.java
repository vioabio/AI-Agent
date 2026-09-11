package com.vio.vioaiagent.security;

import com.vio.vioaiagent.config.OidcPrincipalPolicyProperties;
import com.vio.vioaiagent.config.OidcProperties;
import com.vio.vioaiagent.tools.governance.ToolCapabilityCard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OidcPrincipalPolicyAdapterTest {

    @AfterEach
    void clearContext() {
        com.vio.vioaiagent.common.RequestContext.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void mapsSubjectTenantRoleAndToolsToShortLivedBinding() {
        OidcPrincipalPolicyProperties policy = properties();
        policy.setTenantRoles(Map.of("tenant-a", List.of("trainer")));
        policy.setRoleTools(Map.of("trainer", List.of("getTypeChart")));
        OidcPrincipalPolicyDecisionFixture fixture = fixture(policy);

        PrincipalPolicyDecision result = fixture.adapter().resolve(jwt("alice", "tenant-a", List.of("trainer")));

        assertTrue(result.applicable());
        assertTrue(result.allowed(), () -> result.reason());
        PrincipalPolicy snapshot = result.policy();
        assertEquals("alice", snapshot.principalId());
        assertEquals("tenant-a", snapshot.tenantId());
        assertEquals(Set.of("trainer"), snapshot.roles());
        assertEquals(Set.of("getTypeChart"), snapshot.allowedTools());
        assertTrue(snapshot.tokenBinding().isOidcPolicyBinding());
        assertEquals("alice", snapshot.tokenBinding().subjectId());
        assertEquals("tenant-a", snapshot.tokenBinding().tenantId());
        assertEquals(Set.of("trainer"), snapshot.tokenBinding().roles());
        assertEquals("v1", snapshot.tokenBinding().policyVersion());
        assertFalse(snapshot.tokenBinding().tokenId().contains("alice"));
        assertEquals(64, snapshot.fingerprint().length());
    }

    @Test
    void unknownTenantAndRoleAreRejected() {
        OidcPrincipalPolicyProperties policy = properties();
        policy.setTenantRoles(Map.of("tenant-a", List.of("trainer")));
        OidcPrincipalPolicyDecisionFixture fixture = fixture(policy);

        assertFalse(fixture.adapter().resolve(jwt("alice", "tenant-b", List.of("trainer"))).allowed());
        assertFalse(fixture.adapter().resolve(jwt("alice", "tenant-a", List.of("operator"))).allowed());
    }

    @Test
    void ambiguousOrMissingTenantFailsClosed() {
        OidcPrincipalPolicyProperties policy = properties();
        policy.setTenantRoles(Map.of("tenant-a", List.of("trainer")));
        OidcPrincipalPolicyDecisionFixture fixture = fixture(policy);

        assertFalse(fixture.adapter().resolve(jwt("alice", null, List.of("trainer"))).allowed());
        Jwt ambiguous = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("sub", "alice")
                .claim("tenant_id", List.of("tenant-a", "tenant-b"))
                .claim("roles", List.of("trainer"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        assertFalse(fixture.adapter().resolve(new JwtAuthenticationToken(ambiguous)).allowed());
    }

    @Test
    void explicitPrincipalTenantBindingMustMatchClaim() {
        OidcPrincipalPolicyProperties policy = properties();
        policy.setPrincipalTenants(Map.of("alice", "tenant-a"));
        policy.setTenantRoles(Map.of("tenant-a", List.of("trainer")));
        OidcPrincipalPolicyDecisionFixture fixture = fixture(policy);

        assertTrue(fixture.adapter().resolve(jwt("alice", "tenant-a", List.of("trainer"))).allowed());
        assertFalse(fixture.adapter().resolve(jwt("bob", "tenant-a", List.of("trainer"))).allowed());
        assertFalse(fixture.adapter().resolve(jwt("alice", "tenant-b", List.of("trainer"))).allowed());
    }

    @Test
    void toolAuthorizationRequiresBothPolicyAndCapabilityCard() {
        OidcPrincipalPolicyProperties policy = properties();
        policy.setTenantRoles(Map.of("tenant-a", List.of("trainer")));
        policy.setRoleTools(Map.of("trainer", List.of("getTypeChart")));
        OidcPrincipalPolicyDecisionFixture fixture = fixture(policy);
        PrincipalPolicy snapshot = fixture.adapter()
                .resolve(jwt("alice", "tenant-a", List.of("trainer"))).policy();

        ToolCapabilityCard allowed = new ToolCapabilityCard("getTypeChart", "1.0.0",
                ToolCapabilityCard.RiskLevel.LOW, ToolCapabilityCard.SideEffect.NONE,
                true, 2_000, List.of("trainer"), "{}", "{}");
        ToolCapabilityCard forbidden = new ToolCapabilityCard("writeFile", "1.0.0",
                ToolCapabilityCard.RiskLevel.MEDIUM, ToolCapabilityCard.SideEffect.WRITE,
                false, 2_000, List.of("operator"), "{}", "{}");
        assertTrue(fixture.adapter().authorize(snapshot, allowed).allowed());
        assertFalse(fixture.adapter().authorize(snapshot, forbidden).allowed());
    }

    @Test
    void disabledOidcIsNotApplicableForLegacyCallers() {
        OidcProperties oidc = new OidcProperties();
        OidcPrincipalPolicyAdapter adapter = new OidcPrincipalPolicyAdapter(oidc, properties());
        assertFalse(adapter.resolve(null).applicable());
        assertTrue(adapter.resolve(null).allowed());
    }

    private OidcPrincipalPolicyDecisionFixture fixture(OidcPrincipalPolicyProperties policy) {
        OidcProperties oidc = new OidcProperties();
        oidc.setEnabled(true);
        return new OidcPrincipalPolicyDecisionFixture(
                new OidcPrincipalPolicyAdapter(oidc, policy));
    }

    private OidcPrincipalPolicyProperties properties() {
        OidcPrincipalPolicyProperties properties = new OidcPrincipalPolicyProperties();
        properties.setBindingTtlSeconds(300);
        properties.setMaxRequestsPerMinute(10);
        return properties;
    }

    private JwtAuthenticationToken jwt(String subject, String tenant, List<String> roles) {
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("sub", subject)
                .claim("roles", roles)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        if (tenant != null) builder.claim("tenant_id", tenant);
        return new JwtAuthenticationToken(builder.build(),
                List.of(new SimpleGrantedAuthority("ROLE_trainer")));
    }

    private record OidcPrincipalPolicyDecisionFixture(OidcPrincipalPolicyAdapter adapter) { }
}
