package com.vio.vioaiagent.run;

import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.security.PrincipalPolicy;
import com.vio.vioaiagent.security.TokenBinding;
import com.vio.vioaiagent.tools.governance.ToolExecutionContext;
import com.vio.vioaiagent.tools.governance.ToolExecutionContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * OIDC policy must cross the asynchronous Run boundary as a minimal immutable
 * snapshot; the worker must not fall back to the historical trainer role.
 */
class PokemonAgentRunServiceOidcPolicyTest {

    @AfterEach
    void clearRequestState() {
        RequestContext.clear();
        ToolExecutionContextHolder.clear();
    }

    @Test
    void runWorkerUsesCapturedOidcPolicyAndPersistsOnlySafeSnapshot() throws Exception {
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventRepository events = new InMemoryEventRepository();
        InMemoryConversationRepository conversations = new InMemoryConversationRepository();
        InMemoryChatMessageRepository messages = new InMemoryChatMessageRepository();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        AtomicReference<ToolExecutionContext> observed = new AtomicReference<>();
        PokemonAgentRunService service = new PokemonAgentRunService(runs, events, conversations,
                messages, context -> {
                    observed.set(ToolExecutionContextHolder.current());
                    return "oidc answer";
                }, worker, scheduler);

        Instant expiresAt = Instant.now().plusSeconds(300);
        TokenBinding binding = TokenBinding.oidc("alice", "tenant-a", Set.of("analyst"),
                Set.of("getTypeChart"), "policy-2026-09", 7, expiresAt);
        PrincipalPolicy policy = new PrincipalPolicy("alice", "tenant-a", Set.of("analyst"),
                Set.of("getTypeChart"), "policy-2026-09", expiresAt, binding);
        RequestContext.setPrincipalId("alice");
        RequestContext.setTenantId("tenant-a");
        RequestContext.setRoles(Set.of("analyst"));
        RequestContext.setPrincipalPolicy(policy);
        RequestContext.setTokenBinding(binding);

        try {
            RunView created = service.createRun(new CreateRunCommand("alice", "session-oidc", null,
                    "分析队伍", "sv-1", "singles", "rules-1", "zh-CN", "trace-oidc",
                    null, Duration.ofSeconds(10)));

            AgentRunStatus status = awaitTerminal(runs, created.runId());
            assertEquals(AgentRunStatus.COMPLETED, status);
            ToolExecutionContext context = observed.get();
            assertNotNull(context);
            assertEquals("alice", context.userId());
            assertEquals(created.runId(), context.runId());
            assertEquals("tenant-a", context.tenantId());
            assertEquals(Set.of("analyst"), context.roles());
            assertEquals("policy-2026-09", context.principalPolicy().policyVersion());
            assertEquals(binding, context.tokenBinding());

            AgentEvent runCreated = events.findAfter(created.runId(), 0).stream()
                    .filter(event -> event.type() == AgentEventType.RUN_CREATED)
                    .findFirst().orElseThrow();
            assertEquals("tenant-a", runCreated.payload().get("tenantId"));
            assertEquals(List.of("analyst"), runCreated.payload().get("roles"));
            assertEquals(policy.fingerprint(), runCreated.payload().get("policyFingerprint"));
            assertFalse(runCreated.payload().containsKey("tokenId"));
            assertFalse(runCreated.payload().containsKey("jwt"));
        } finally {
            service.shutdown();
        }
    }

    private AgentRunStatus awaitTerminal(InMemoryRunRepository runs, String runId)
            throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(3);
        AgentRunStatus status = AgentRunStatus.CREATED;
        while (Instant.now().isBefore(deadline)) {
            status = runs.findById(runId).orElseThrow().status();
            if (status.isTerminal()) return status;
            Thread.sleep(10);
        }
        return status;
    }
}
