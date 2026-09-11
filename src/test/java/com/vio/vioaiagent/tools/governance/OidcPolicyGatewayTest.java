package com.vio.vioaiagent.tools.governance;

import com.vio.vioaiagent.config.OidcPrincipalPolicyProperties;
import com.vio.vioaiagent.config.OidcProperties;
import com.vio.vioaiagent.config.SecurityProperties;
import com.vio.vioaiagent.security.AuditLogger;
import com.vio.vioaiagent.security.OidcPrincipalPolicyAdapter;
import com.vio.vioaiagent.security.PrincipalPolicy;
import com.vio.vioaiagent.security.TokenBinding;
import com.vio.vioaiagent.security.TokenStore;
import com.vio.vioaiagent.security.CommandGuard;
import com.vio.vioaiagent.security.HitlManager;
import com.vio.vioaiagent.security.ParameterMasker;
import com.vio.vioaiagent.security.PathGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OidcPolicyGatewayTest {
    private ToolExecutionGateway gateway;

    @AfterEach
    void cleanup() {
        if (gateway != null) gateway.shutdown();
        SecurityContextHolder.clearContext();
    }

    @Test
    void oidcPolicyUsesTokenStoreScopeAndQuotaWithoutBypass() {
        OidcPrincipalPolicyProperties policyProperties = new OidcPrincipalPolicyProperties();
        policyProperties.setTenantRoles(Map.of("tenant-a", List.of("trainer")));
        policyProperties.setRoleTools(Map.of("trainer", List.of("getTypeChart")));
        OidcProperties oidc = new OidcProperties();
        oidc.setEnabled(true);
        OidcPrincipalPolicyAdapter adapter = new OidcPrincipalPolicyAdapter(oidc, policyProperties);
        PrincipalPolicy policy = adapter.resolve(jwt("alice", "tenant-a")).policy();

        TokenStore store = new TokenStore(new SecurityProperties(true, Map.of(), List.of(), List.of()));
        store.register(policy.tokenBinding());
        ParameterMasker masker = new ParameterMasker();
        gateway = new ToolExecutionGateway(new HitlManager(),
                new PathGuard(List.of(System.getProperty("user.dir") + "/tmp")),
                new CommandGuard(List.of()), masker, new AuditLogger(masker), store, null, true);
        AtomicInteger calls = new AtomicInteger();
        gateway.wrap(callback("getTypeChart", calls));
        ToolExecutionContext context = new ToolExecutionContext("alice", "run-oidc",
                policy.roles(), "trace", null, null, policy.tokenBinding(), policy);

        assertEquals(ToolGovernanceResult.Status.EXECUTED,
                gateway.execute("getTypeChart", "{}", context).status());
        assertEquals(1, calls.get());
    }

    @Test
    void missingRegistryBindingRemainsFailClosed() {
        OidcPrincipalPolicyProperties policyProperties = new OidcPrincipalPolicyProperties();
        policyProperties.setTenantRoles(Map.of("tenant-a", List.of("trainer")));
        policyProperties.setRoleTools(Map.of("trainer", List.of("getTypeChart")));
        OidcProperties oidc = new OidcProperties();
        oidc.setEnabled(true);
        PrincipalPolicy policy = new OidcPrincipalPolicyAdapter(oidc, policyProperties)
                .resolve(jwt("alice", "tenant-a")).policy();
        TokenStore store = new TokenStore(new SecurityProperties(true, Map.of(), List.of(), List.of()));
        ParameterMasker masker = new ParameterMasker();
        gateway = new ToolExecutionGateway(new HitlManager(),
                new PathGuard(List.of(System.getProperty("user.dir") + "/tmp")),
                new CommandGuard(List.of()), masker, new AuditLogger(masker), store, null, true);
        AtomicInteger calls = new AtomicInteger();
        gateway.wrap(callback("getTypeChart", calls));
        ToolExecutionContext context = new ToolExecutionContext("alice", "run-oidc-missing",
                policy.roles(), "trace", null, null, policy.tokenBinding(), policy);

        ToolGovernanceResult result = gateway.execute("getTypeChart", "{}", context);
        assertEquals(ToolGovernanceResult.Status.REJECTED, result.status());
        assertEquals(0, calls.get());
    }

    private JwtAuthenticationToken jwt(String subject, String tenant) {
        Jwt token = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("sub", subject)
                .claim("tenant_id", tenant)
                .claim("roles", List.of("trainer"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        return new JwtAuthenticationToken(token,
                List.of(new SimpleGrantedAuthority("ROLE_trainer")));
    }

    private ToolCallback callback(String name, AtomicInteger calls) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                calls.incrementAndGet();
                return "ok";
            }
        };
    }
}
