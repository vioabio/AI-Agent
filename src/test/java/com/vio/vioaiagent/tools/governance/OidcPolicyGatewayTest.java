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
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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

    @Test
    void capturedBrowserPolicyCannotCallToolsAfterAbsoluteDeadlineEvenIfRegistryWasRefreshed() throws Exception {
        // Construct at an earlier trusted login time, then execute at current
        // wall time. No sleeps or real provider calls are required.
        Instant earlier = Instant.now().minusSeconds(120);
        Instant deadline = earlier.plusSeconds(30);
        OidcProperties oidc = new OidcProperties();
        oidc.setEnabled(true);
        OidcPrincipalPolicyProperties props = new OidcPrincipalPolicyProperties();
        props.setTenantRoles(Map.of("tenant-a", List.of("trainer")));
        props.setRoleTools(Map.of("trainer", List.of("getTypeChart")));
        OidcIdToken token = new OidcIdToken("fixture", earlier, earlier.plusSeconds(900),
                Map.of("iss", "https://id.example.test", "sub", "alice", "tenant_id", "tenant-a",
                        "roles", List.of("trainer")));
        OAuth2AuthenticationToken browser = new OAuth2AuthenticationToken(
                new DefaultOidcUser(List.of(new OidcUserAuthority(token)), token),
                List.of(new SimpleGrantedAuthority("ROLE_trainer")), "vio");
        PrincipalPolicy captured = new OidcPrincipalPolicyAdapter(oidc, props,
                Clock.fixed(earlier, ZoneOffset.UTC)).resolve(browser, deadline).policy();
        assertEquals(deadline, captured.tokenBinding().expiresAt());
        TokenStore store = new TokenStore(new SecurityProperties(true, Map.of(), List.of(), List.of()));
        store.register(TokenBinding.oidc("alice", "tenant-a", captured.roles(), captured.allowedTools(),
                captured.policyVersion(), props.maxRequestsPerMinute(), Instant.now().plusSeconds(300)));
        ParameterMasker masker = new ParameterMasker();
        gateway = new ToolExecutionGateway(new HitlManager(),
                new PathGuard(List.of(System.getProperty("user.dir") + "/tmp")),
                new CommandGuard(List.of()), masker, new AuditLogger(masker), store, null, true);
        AtomicInteger calls = new AtomicInteger();
        gateway.wrap(callback("getTypeChart", calls));
        ToolExecutionContext context = new ToolExecutionContext("alice", "expired-browser-run",
                captured.roles(), "trace", null, null, captured.tokenBinding(), captured);
        ToolGovernanceResult result = CompletableFuture.supplyAsync(() ->
                gateway.execute("getTypeChart", "{}", context)).get(10, TimeUnit.SECONDS);
        assertEquals(ToolGovernanceResult.Status.REJECTED, result.status());
        assertEquals(0, calls.get());
    }

    @Test
    void bindingThatExpiresAfterCallerAuthorizationCannotStartCallback() {
        PrincipalPolicy policy = policy();
        AtomicInteger scopeChecks = new AtomicInteger();
        TokenStore store = new TokenStore(new SecurityProperties(true, Map.of(), List.of(), List.of())) {
            @Override
            public String authorizeTool(TokenBinding binding, String toolName) {
                String result = super.authorizeTool(binding, toolName);
                if (scopeChecks.incrementAndGet() == 1) register(expiredCopy(binding));
                return result;
            }
        };
        store.register(policy.tokenBinding());
        createGateway(store);
        AtomicInteger calls = new AtomicInteger();
        gateway.wrap(callback("getTypeChart", calls));
        ToolGovernanceResult result = gateway.execute("getTypeChart", "{}", context(policy, "dispatch-expiry"));
        assertEquals(ToolGovernanceResult.Status.REJECTED, result.status());
        assertEquals(0, calls.get(), "Caller-side approval must not authorize a later expired callback");
        assertEquals(2, scopeChecks.get(), "The actual executor boundary must recheck scope");
    }

    @Test
    void bindingThatExpiresDuringFailedAttemptCannotAuthorizeRetry() {
        PrincipalPolicy policy = policy();
        TokenStore store = new TokenStore(new SecurityProperties(true, Map.of(), List.of(), List.of()));
        store.register(policy.tokenBinding());
        createGateway(store);
        AtomicInteger calls = new AtomicInteger();
        gateway.wrap(new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("getTypeChart").description("retry expiry fixture")
                        .inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                calls.incrementAndGet();
                // Make the credential expired before the permitted low-risk
                // retry, without sleeping or relying on a timing race.
                store.register(expiredCopy(policy.tokenBinding()));
                throw new IllegalStateException("first attempt failed");
            }
        });
        ToolGovernanceResult result = gateway.execute("getTypeChart", "{}", context(policy, "retry-expiry"));
        assertEquals(ToolGovernanceResult.Status.REJECTED, result.status());
        assertEquals(1, calls.get(), "An expired credential must prevent the second callback");
    }

    private PrincipalPolicy policy() {
        OidcProperties oidc = new OidcProperties();
        oidc.setEnabled(true);
        OidcPrincipalPolicyProperties props = new OidcPrincipalPolicyProperties();
        props.setTenantRoles(Map.of("tenant-a", List.of("trainer")));
        props.setRoleTools(Map.of("trainer", List.of("getTypeChart")));
        return new OidcPrincipalPolicyAdapter(oidc, props).resolve(jwt("alice", "tenant-a")).policy();
    }

    private TokenBinding expiredCopy(TokenBinding binding) {
        return TokenBinding.oidc(binding.subjectId(), binding.tenantId(), binding.roles(), binding.allowedTools(),
                binding.policyVersion(), binding.maxRequestsPerMinute(), Instant.EPOCH);
    }

    private ToolExecutionContext context(PrincipalPolicy policy, String runId) {
        return new ToolExecutionContext("alice", runId, policy.roles(), "trace", null, null,
                policy.tokenBinding(), policy);
    }

    private void createGateway(TokenStore store) {
        ParameterMasker masker = new ParameterMasker();
        gateway = new ToolExecutionGateway(new HitlManager(),
                new PathGuard(List.of(System.getProperty("user.dir") + "/tmp")),
                new CommandGuard(List.of()), masker, new AuditLogger(masker), store, null, true);
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
