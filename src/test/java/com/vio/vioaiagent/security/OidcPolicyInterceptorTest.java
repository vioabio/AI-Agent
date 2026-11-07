package com.vio.vioaiagent.security;

import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.config.OidcPrincipalPolicyProperties;
import com.vio.vioaiagent.config.OidcProperties;
import com.vio.vioaiagent.config.SecurityProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OidcPolicyInterceptorTest {
    private final SecurityProperties security = new SecurityProperties(
            true, Map.of(), List.of(System.getProperty("user.dir") + "/tmp"), List.of());

    @AfterEach
    void cleanup() {
        RequestContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedOidcRequestGetsTenantRoleAndShortBinding() throws Exception {
        TokenStore tokens = new TokenStore(security);
        OidcProperties oidc = oidcEnabled();
        OidcPrincipalPolicyProperties props = policyProperties();
        OidcPrincipalPolicyAdapter adapter = new OidcPrincipalPolicyAdapter(oidc, props);
        ApiKeyAuthInterceptor interceptor = new ApiKeyAuthInterceptor(tokens, security);
        interceptor.setOidcPolicyAdapter(adapter);
        // ADR-002 步骤 1：内部归属键的 issuer 维度来自拦截器持有的 OidcProperties；
        // 手工构造拦截器时必须显式传入，否则归属键派生会 fail-closed 拒绝。
        interceptor.setOidcProperties(oidc);
        SecurityContextHolder.getContext().setAuthentication(jwt("alice", "tenant-a", List.of("trainer")));

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(new MockHttpServletRequest("GET", "/chat/runs"), response, null));
        assertEquals("alice", RequestContext.getPrincipalId());
        assertEquals("tenant-a", RequestContext.getTenantId());
        assertEquals(java.util.Set.of("trainer"), RequestContext.getRoles());
        assertNotNull(RequestContext.getPrincipalPolicy());
        assertTrue(RequestContext.getTokenBinding().isOidcPolicyBinding());
        // 归属键必须在身份边界写入，且确实承载 tenant（ADR-002 步骤 1）。
        assertEquals(InternalPrincipalKey.fromOidc(ISSUER, "tenant-a", "alice"),
                RequestContext.getOwnerId());
        assertNotNull(tokens.authorizeTool(RequestContext.getTokenBinding(), "writeFile"));
        assertNull(tokens.authorizeTool(RequestContext.getTokenBinding(), "getTypeChart"));
    }

    @Test
    void policyFailureReturnsForbiddenAndClearsContext() throws Exception {
        TokenStore tokens = new TokenStore(security);
        OidcProperties oidc = oidcEnabled();
        OidcPrincipalPolicyAdapter adapter = new OidcPrincipalPolicyAdapter(oidc, policyProperties());
        ApiKeyAuthInterceptor interceptor = new ApiKeyAuthInterceptor(tokens, security);
        interceptor.setOidcPolicyAdapter(adapter);
        interceptor.setOidcProperties(oidc);
        SecurityContextHolder.getContext().setAuthentication(jwt("alice", "unknown", List.of("trainer")));

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(new MockHttpServletRequest("GET", "/chat/runs"), response, null));
        assertEquals(403, response.getStatus());
        assertNull(RequestContext.getPrincipalId());
        assertNull(RequestContext.getTenantId());
        assertNull(RequestContext.getPrincipalPolicy());
        // 被拒绝的主体不得留下归属键，否则下游可能把它当成已授权主体。
        assertNull(RequestContext.getOwnerId(), "策略拒绝后不得残留归属键");
    }

    /** 与 {@link #jwt} 夹具中的 iss 保持一致的启用态配置。 */
    private static final String ISSUER = "https://id.example.com/realms/vio";

    private OidcProperties oidcEnabled() {
        OidcProperties oidc = new OidcProperties();
        oidc.setEnabled(true);
        oidc.setIssuerUri(ISSUER);
        return oidc;
    }

    @Test
    void legacyApiKeyPathDoesNotReceiveOidcPolicy() throws Exception {
        SecurityProperties apiSecurity = new SecurityProperties(
                true, Map.of("sk-test", "compat:default"),
                List.of(System.getProperty("user.dir") + "/tmp"), List.of());
        TokenStore tokens = new TokenStore(apiSecurity);
        OidcProperties oidc = new OidcProperties();
        oidc.setEnabled(true);
        ApiKeyAuthInterceptor interceptor = new ApiKeyAuthInterceptor(tokens, apiSecurity);
        interceptor.setOidcPolicyAdapter(new OidcPrincipalPolicyAdapter(oidc, policyProperties()));
        SecurityContextHolder.clearContext();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/chat/runs");
        request.addHeader("X-API-Key", "sk-test");
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), null));
        assertNull(RequestContext.getPrincipalPolicy());
        assertNull(RequestContext.getTenantId());
        assertNotNull(RequestContext.getTokenBinding());
        assertFalse(RequestContext.getTokenBinding().isOidcPolicyBinding());
    }

    private OidcPrincipalPolicyProperties policyProperties() {
        OidcPrincipalPolicyProperties props = new OidcPrincipalPolicyProperties();
        props.setTenantRoles(Map.of("tenant-a", List.of("trainer")));
        props.setRoleTools(Map.of("trainer", List.of("getTypeChart")));
        return props;
    }

    private JwtAuthenticationToken jwt(String subject, String tenant, List<String> roles) {
        Jwt token = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("sub", subject)
                .claim("tenant_id", tenant)
                .claim("roles", roles)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        return new JwtAuthenticationToken(token,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + roles.get(0))));
    }
}
