package com.vio.vioaiagent.tools.governance;

import com.vio.vioaiagent.security.AuditLogger;
import com.vio.vioaiagent.security.AuditEntry;
import com.vio.vioaiagent.security.AuditWriteException;
import com.vio.vioaiagent.security.CommandGuard;
import com.vio.vioaiagent.security.HitlManager;
import com.vio.vioaiagent.security.ParameterMasker;
import com.vio.vioaiagent.security.PathGuard;
import com.vio.vioaiagent.security.ToolAuditSink;
import com.vio.vioaiagent.security.TokenBinding;
import com.vio.vioaiagent.security.TokenStore;
import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.config.SecurityProperties;
import com.vio.vioaiagent.run.EffectLedgerRepository;
import com.vio.vioaiagent.run.ToolEffectLedgerEntry;
import com.vio.vioaiagent.run.ToolEffectStatus;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.junit.jupiter.api.AfterEach;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M4 网关最小安全回归：未知工具、HITL、Schema 和幂等。 */
class ToolExecutionGatewayTest {

    @Test
    void actualExecutorCallbackReceivesRunScopeAndDoesNotContaminateCaller() {
        ToolExecutionGateway gateway=gateway();
        java.util.concurrent.atomic.AtomicReference<ToolExecutionContext> seen=new java.util.concurrent.atomic.AtomicReference<>();
        ToolCallback tool=new ToolCallback(){
            public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name("getTypeChart").description("scope test").inputSchema("{}").build();}
            public String call(String input){seen.set(ToolExecutionContextHolder.peek());return "ok";}
        };
        gateway.wrap(tool);
        var context=new ToolExecutionContext("alice","run-scope-test",Set.of("trainer"),"trace-scope",null,null);
        try {
            assertEquals(ToolGovernanceResult.Status.EXECUTED,gateway.execute("getTypeChart","{}",context).status());
            assertEquals(context,seen.get());
            org.junit.jupiter.api.Assertions.assertNull(ToolExecutionContextHolder.peek());
        }finally{gateway.shutdown();}
    }

    @AfterEach
    void clearAuthenticationContext() {
        SecurityContextHolder.clearContext();
        RequestContext.clear();
    }

    private ToolExecutionGateway gateway() {
        ParameterMasker masker = new ParameterMasker();
        return new ToolExecutionGateway(new HitlManager(),
                new PathGuard(List.of(System.getProperty("user.dir") + "/tmp")),
                new CommandGuard(List.of()), masker, new AuditLogger(masker));
    }

    @Test
    void unknownToolIsFailClosed() {
        ToolExecutionGateway gateway = gateway();
        ToolCallback callback = callback("unregistered", "{}");
        gateway.wrap(callback);

        ToolGovernanceResult result = gateway.execute("unregistered", "{}",
                new ToolExecutionContext("u1", "r1", Set.of("trainer"), "t1", null, null));

        assertEquals(ToolGovernanceResult.Status.REJECTED, result.status());
    }

    @Test
    void mediumRiskRequiresApproval() {
        ToolExecutionGateway gateway = gateway();
        ToolCallback callback = callback("writeFile", "{\"required\":[\"fileName\"]}");
        gateway.wrap(callback);

        ToolGovernanceResult result = gateway.execute("writeFile", "{\"fileName\":\"a.txt\"}",
                new ToolExecutionContext("u1", "r1", Set.of("operator"), "t1", "k1", null));

        assertEquals(ToolGovernanceResult.Status.WAITING_APPROVAL, result.status());
        assertTrue(result.approvalId() != null && !result.approvalId().isBlank());
    }

    @Test
    void trainerCannotUseAdminWriteToolEvenWhenRegistered() {
        ToolExecutionGateway gateway = gateway();
        AtomicInteger calls = new AtomicInteger();
        gateway.wrap(callback("writeFile", "{\"required\":[\"fileName\"]}", calls));

        ToolGovernanceResult result = gateway.execute("writeFile", "{\"fileName\":\"a.txt\"}",
                new ToolExecutionContext("u1", "r1", Set.of("trainer"), "t1", "k-trainer", null));

        assertEquals(ToolGovernanceResult.Status.REJECTED, result.status());
        assertEquals(0, calls.get());
        gateway.shutdown();
    }

    @Test
    void trainerCannotReadServerFileEvenWhenHighRiskSetIsRegistered() {
        ToolExecutionGateway gateway = gateway();
        AtomicInteger calls = new AtomicInteger();
        gateway.wrap(callback("readFile", "{\"required\":[\"fileName\"]}", calls));

        ToolGovernanceResult result = gateway.execute("readFile", "{\"fileName\":\"report.txt\"}",
                new ToolExecutionContext("u1", "r1", Set.of("trainer"), "t1", "k-trainer-read", null));

        assertEquals(ToolGovernanceResult.Status.REJECTED, result.status());
        assertEquals(0, calls.get());
        gateway.shutdown();
    }

    @Test
    void idempotentReadDoesNotExecuteTwice() {
        ToolExecutionGateway gateway = gateway();
        AtomicInteger calls = new AtomicInteger();
        ToolCallback callback = callback("getTypeChart", "{}", calls);
        gateway.wrap(callback);
        ToolExecutionContext context = new ToolExecutionContext(
                "u1", "r1", Set.of("trainer"), "t1", "read-1", null);

        gateway.execute("getTypeChart", "{}", context);
        gateway.execute("getTypeChart", "{}", context);

        assertEquals(1, calls.get());
    }

    @Test
    void concurrentIdempotentReadExecutesOnlyOnce() throws Exception {
        ToolExecutionGateway gateway = gateway();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ToolCallback callback = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("concurrentRead")
                        .description("concurrentRead").inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                calls.incrementAndGet();
                entered.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return "ok";
            }
        };
        gateway.wrapMcp(callback, new ToolCapabilityCard("concurrentRead", "1.0.0",
                ToolCapabilityCard.RiskLevel.LOW, ToolCapabilityCard.SideEffect.NONE,
                true, 2_000, List.of("trainer"), "{}", "{}"));
        ToolExecutionContext context = new ToolExecutionContext(
                "u1", "r-concurrent", Set.of("trainer"), "t-concurrent", "same-key", null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ToolGovernanceResult> first = pool.submit(() ->
                    gateway.execute("concurrentRead", "{}", context));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            Future<ToolGovernanceResult> second = pool.submit(() ->
                    gateway.execute("concurrentRead", "{}", context));
            // 第二个调用必须在幂等临界区等待，而不是同时进入 callback。
            Thread.sleep(50);
            assertEquals(1, calls.get());
            release.countDown();
            assertEquals(ToolGovernanceResult.Status.EXECUTED, first.get(2, TimeUnit.SECONDS).status());
            assertEquals(ToolGovernanceResult.Status.DEDUPLICATED, second.get(2, TimeUnit.SECONDS).status());
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
            pool.shutdownNow();
            gateway.shutdown();
        }
    }

    @Test
    void nestedNetworkTargetIsStillRejected() {
        ToolExecutionGateway gateway = gateway();
        ToolCallback callback = callback("nestedSearch",
                "{\"type\":\"object\",\"properties\":{\"options\":{\"type\":\"object\"}}}");
        ToolCapabilityCard card = new ToolCapabilityCard("nestedSearch", "1.0.0",
                ToolCapabilityCard.RiskLevel.LOW, ToolCapabilityCard.SideEffect.NETWORK,
                true, 2_000, List.of("trainer"), "{}", "{}");
        gateway.wrapMcp(callback, card);

        ToolGovernanceResult result = gateway.execute("nestedSearch",
                "{\"options\":{\"url\":\"http://127.0.0.1:8123/internal\"}}",
                new ToolExecutionContext("u1", "r1", Set.of("trainer"), "t1", "read-2", null));

        assertEquals(ToolGovernanceResult.Status.REJECTED, result.status());
    }

    @Test
    void ipv4MappedIpv6PrivateTargetIsRejected() {
        ToolExecutionGateway gateway = gateway();
        ToolCallback callback = callback("mappedSearch", "{}");
        ToolCapabilityCard card = new ToolCapabilityCard("mappedSearch", "1.0.0",
                ToolCapabilityCard.RiskLevel.LOW, ToolCapabilityCard.SideEffect.NETWORK,
                true, 2_000, List.of("trainer"), "{}", "{}");
        gateway.wrapMcp(callback, card);

        ToolGovernanceResult result = gateway.execute("mappedSearch",
                "{\"url\":\"http://[::ffff:127.0.0.1]:8123/internal\"}",
                new ToolExecutionContext("u1", "r-mapped", Set.of("trainer"), "t-mapped", "read-3", null));

        assertEquals(ToolGovernanceResult.Status.REJECTED, result.status());
        gateway.shutdown();
    }

    @Test
    void nestedSchemaConstraintsAreEnforcedBeforeToolCall() {
        ToolExecutionGateway gateway = gateway();
        AtomicInteger calls = new AtomicInteger();
        ToolCallback callback = callback("schemaNested", "{}", calls);
        ToolCapabilityCard card = new ToolCapabilityCard("schemaNested", "1.0.0",
                ToolCapabilityCard.RiskLevel.LOW, ToolCapabilityCard.SideEffect.NONE,
                true, 2_000, List.of("trainer"),
                "{\"type\":\"object\",\"additionalProperties\":false,"
                        + "\"required\":[\"options\"],\"properties\":{\"options\":{"
                        + "\"type\":\"object\",\"required\":[\"mode\",\"limit\"],"
                        + "\"properties\":{\"mode\":{\"type\":\"string\",\"enum\":[\"safe\"]},"
                        + "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":3}}}}}", "{}");
        gateway.wrapMcp(callback, card);

        ToolGovernanceResult badEnum = gateway.execute("schemaNested",
                "{\"options\":{\"mode\":\"unsafe\",\"limit\":2}}",
                new ToolExecutionContext("u1", "r-schema", Set.of("trainer"), "t1", "schema-1", null));
        assertEquals(ToolGovernanceResult.Status.REJECTED, badEnum.status());
        ToolGovernanceResult badRange = gateway.execute("schemaNested",
                "{\"options\":{\"mode\":\"safe\",\"limit\":9}}",
                new ToolExecutionContext("u1", "r-schema", Set.of("trainer"), "t1", "schema-2", null));
        assertEquals(ToolGovernanceResult.Status.REJECTED, badRange.status());
        assertEquals(0, calls.get());
        gateway.shutdown();
    }

    @Test
    void tokenBindingEnforcesToolScopeAndMinuteQuota() {
        ParameterMasker masker = new ParameterMasker();
        TokenStore store = new TokenStore(SecurityProperties.defaults());
        TokenBinding binding = new TokenBinding("token-scope", "pokemon", Set.of("getTypeChart"), 1, null);
        store.register(binding);
        ToolExecutionGateway gateway = new ToolExecutionGateway(new HitlManager(),
                new PathGuard(List.of(System.getProperty("user.dir") + "/tmp")),
                new CommandGuard(List.of()), masker, new AuditLogger(masker), store);
        AtomicInteger calls = new AtomicInteger();
        gateway.wrap(callback("getTypeChart", "{}", calls));

        ToolExecutionContext context = new ToolExecutionContext(
                "principal", "run-token", Set.of("trainer"), "trace-token", null, null, binding);
        assertEquals(ToolGovernanceResult.Status.EXECUTED,
                gateway.execute("getTypeChart", "{}", context).status());
        assertEquals(ToolGovernanceResult.Status.REJECTED,
                gateway.execute("getTypeChart", "{}", context).status());
        assertEquals(1, calls.get());

        ToolExecutionContext forbidden = context.withIdempotencyKey("different")
                .withTokenBinding(new TokenBinding("token-scope", "pokemon", Set.of("web_search"), 0, null));
        assertEquals(ToolGovernanceResult.Status.REJECTED,
                gateway.execute("getTypeChart", "{}", forbidden).status());
        gateway.shutdown();
    }

    @Test
    void oidcAuthenticatedWithoutTokenBindingIsRejectedBeforeCallback() {
        ParameterMasker masker = new ParameterMasker();
        TokenStore store = new TokenStore(SecurityProperties.defaults());
        ToolExecutionGateway gateway = new ToolExecutionGateway(new HitlManager(),
                new PathGuard(List.of(System.getProperty("user.dir") + "/tmp")),
                new CommandGuard(List.of()), masker, new AuditLogger(masker), store);
        AtomicInteger calls = new AtomicInteger();
        gateway.wrap(callback("getTypeChart", "{}", calls));

        // 模拟 Resource Server 已认证的 JWT 主体；OIDC 分支没有 TokenBinding。
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("oidc-user", "test", "ROLE_trainer"));
        RequestContext.setPrincipalId("oidc-user");
        ToolExecutionContext context = new ToolExecutionContext(
                "oidc-user", "run-oidc", Set.of("trainer"), "trace-oidc", "idem-oidc", null);

        ToolGovernanceResult result = gateway.execute("getTypeChart", "{}", context);

        assertEquals(ToolGovernanceResult.Status.REJECTED, result.status());
        assertTrue(result.output().contains("TokenBinding"));
        assertEquals(0, calls.get(), "OIDC 未绑定工具策略时不得进入工具回调");
        gateway.shutdown();
    }

    @Test
    void oidcBindingGateAlsoRejectsContextAfterWorkerSecurityContextIsLost() {
        ParameterMasker masker = new ParameterMasker();
        ToolExecutionGateway gateway = new ToolExecutionGateway(new HitlManager(),
                new PathGuard(List.of(System.getProperty("user.dir") + "/tmp")),
                new CommandGuard(List.of()), masker, new AuditLogger(masker), null, null, true);
        AtomicInteger calls = new AtomicInteger();
        gateway.wrap(callback("getTypeChart", "{}", calls));

        // OIDC 开关由 Spring 装配阶段锁定；即便异步 worker 没有 SecurityContext，
        // 缺失 binding 仍然 fail-closed。
        SecurityContextHolder.clearContext();
        ToolExecutionContext context = new ToolExecutionContext(
                "oidc-user", "run-oidc-worker", Set.of("trainer"), "trace-oidc-worker", "idem", null);

        ToolGovernanceResult result = gateway.execute("getTypeChart", "{}", context);

        assertEquals(ToolGovernanceResult.Status.REJECTED, result.status());
        assertEquals(0, calls.get());
        gateway.shutdown();
    }

    @Test
    void apiKeyBindingStillWorksWhenOidcGateIsEnabled() {
        ParameterMasker masker = new ParameterMasker();
        TokenStore store = new TokenStore(SecurityProperties.defaults());
        TokenBinding binding = new TokenBinding("api-key-token", "pokemon", Set.of("getTypeChart"), 2, null);
        store.register(binding);
        ToolExecutionGateway gateway = new ToolExecutionGateway(new HitlManager(),
                new PathGuard(List.of(System.getProperty("user.dir") + "/tmp")),
                new CommandGuard(List.of()), masker, new AuditLogger(masker), store, null, true);
        AtomicInteger calls = new AtomicInteger();
        gateway.wrap(callback("getTypeChart", "{}", calls));

        ToolExecutionContext context = new ToolExecutionContext(
                "api-key-principal", "run-api-key", Set.of("trainer"), "trace-api-key", "idem-api-key", null, binding);

        assertEquals(ToolGovernanceResult.Status.EXECUTED,
                gateway.execute("getTypeChart", "{}", context).status());
        assertEquals(1, calls.get());
        gateway.shutdown();
    }

    @Test
    void unavailableEffectLedgerRejectsWriteBeforeCallback() {
        HitlManager hitl = new HitlManager();
        AtomicInteger calls = new AtomicInteger();
        ToolExecutionGateway gateway = gatewayWithLedger(hitl, new EffectLedgerRepository() {
            @Override
            public Optional<ToolEffectLedgerEntry> find(String runId, int stepNo, String toolName,
                                                         String toolVersion, String idempotencyKey,
                                                         String parameterHash) {
                throw new IllegalStateException("storage unavailable");
            }

            @Override
            public boolean reserve(ToolEffectLedgerEntry entry) {
                throw new IllegalStateException("storage unavailable");
            }

            @Override
            public ToolEffectLedgerEntry complete(ToolEffectLedgerEntry entry) {
                throw new IllegalStateException("storage unavailable");
            }
        });
        gateway.wrapMcp(callback("ledgerWrite", "{}", calls), writeCard("ledgerWrite"));
        ToolExecutionContext context = new ToolExecutionContext(
                "operator-1", "run-ledger-down", Set.of("operator"), "trace-ledger-down", "idem-ledger-down", null);

        ToolGovernanceResult pending = gateway.execute("ledgerWrite", "{}", context);
        assertEquals(ToolGovernanceResult.Status.WAITING_APPROVAL, pending.status());
        assertTrue(pending.approvalId() != null && !pending.approvalId().isBlank());
        hitl.decide(pending.approvalId(), context.userId(), context.runId(), true, "approved");

        ToolGovernanceResult result = gateway.execute("ledgerWrite", "{}", context);
        assertEquals(ToolGovernanceResult.Status.REJECTED, result.status());
        assertTrue(result.output().contains("副作用账本"));
        assertEquals(0, calls.get(), "账本不可用时不得调用外部写工具");
        gateway.shutdown();
    }

    @Test
    void durableAuditFailureNeverReturnsExecuted() {
        ParameterMasker masker = new ParameterMasker();
        AtomicInteger calls = new AtomicInteger();
        ToolAuditSink failingDurableSink = new ToolAuditSink() {
            @Override
            public boolean durable() { return true; }

            @Override
            public boolean failClosedOnError() { return true; }

            @Override
            public void record(AuditEntry entry) {
                throw new AuditWriteException("synthetic audit outage");
            }
        };
        ToolExecutionGateway gateway = new ToolExecutionGateway(new HitlManager(),
                new PathGuard(List.of(System.getProperty("user.dir") + "/tmp")),
                new CommandGuard(List.of()), masker, failingDurableSink, null, null, false);
        gateway.wrapMcp(callback("durableAuditRead", "{}", calls), new ToolCapabilityCard(
                "durableAuditRead", "1.0.0", ToolCapabilityCard.RiskLevel.LOW,
                ToolCapabilityCard.SideEffect.NONE, true, 2_000, List.of("trainer"), "{}", "{}"));

        ToolGovernanceResult result = gateway.execute("durableAuditRead", "{}",
                new ToolExecutionContext("u-audit", "run-audit", Set.of("trainer"),
                        "trace-audit", "idem-audit", null));

        assertEquals(ToolGovernanceResult.Status.FAILED, result.status());
        assertTrue(!result.toModelText().contains("synthetic audit outage"));
        assertEquals(1, calls.get(), "审计失败发生在回调之后，结果必须阻止成功回传");
        gateway.shutdown();
    }

    @Test
    void existingStartedEffectIsNotReplayed() {
        HitlManager hitl = new HitlManager();
        AtomicInteger calls = new AtomicInteger();
        ToolExecutionContext context = new ToolExecutionContext(
                "operator-2", "run-ledger-started", Set.of("operator"), "trace-ledger-started", "idem-started", null);
        ToolEffectLedgerEntry started = ToolEffectLedgerEntry.started(
                context.runId(), Math.floorMod(context.idempotencyKey().hashCode(), Integer.MAX_VALUE),
                "ledgerWrite", "1.0.0", context.idempotencyKey(),
                // 网关对规范化空 JSON 的 SHA-256 摘要。
                "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a");
        ToolExecutionGateway gateway = gatewayWithLedger(hitl, new FixedEffectLedger(started));
        gateway.wrapMcp(callback("ledgerWrite", "{}", calls), writeCard("ledgerWrite"));

        ToolGovernanceResult pending = gateway.execute("ledgerWrite", "{}", context);
        assertEquals(ToolGovernanceResult.Status.WAITING_APPROVAL, pending.status());
        hitl.decide(pending.approvalId(), context.userId(), context.runId(), true, "approved");

        ToolGovernanceResult result = gateway.execute("ledgerWrite", "{}", context);
        assertEquals(ToolGovernanceResult.Status.REJECTED, result.status());
        assertTrue(result.output().contains("STARTED"));
        assertEquals(0, calls.get(), "已有 STARTED 记录只允许人工核对，不得自动重放");
        gateway.shutdown();
    }

    @Test
    void reserveFalseWithoutReadableRowRejectsWrite() {
        HitlManager hitl = new HitlManager();
        AtomicInteger calls = new AtomicInteger();
        ToolExecutionGateway gateway = gatewayWithLedger(hitl, new EffectLedgerRepository() {
            @Override
            public Optional<ToolEffectLedgerEntry> find(String runId, int stepNo, String toolName,
                                                         String toolVersion, String idempotencyKey,
                                                         String parameterHash) {
                return Optional.empty();
            }

            @Override
            public boolean reserve(ToolEffectLedgerEntry entry) {
                return false;
            }

            @Override
            public ToolEffectLedgerEntry complete(ToolEffectLedgerEntry entry) {
                return entry;
            }
        });
        gateway.wrapMcp(callback("ledgerRace", "{}", calls), writeCard("ledgerRace"));
        ToolExecutionContext context = new ToolExecutionContext(
                "operator-3", "run-ledger-race", Set.of("operator"), "trace-ledger-race", "idem-race", null);
        ToolGovernanceResult pending = gateway.execute("ledgerRace", "{}", context);
        assertEquals(ToolGovernanceResult.Status.WAITING_APPROVAL, pending.status());
        hitl.decide(pending.approvalId(), context.userId(), context.runId(), true, "approved");

        ToolGovernanceResult result = gateway.execute("ledgerRace", "{}", context);
        assertEquals(ToolGovernanceResult.Status.REJECTED, result.status());
        assertEquals(0, calls.get(), "reserve 结果无法确认时不得假装取得执行权");
        gateway.shutdown();
    }

    private ToolExecutionGateway gatewayWithLedger(HitlManager hitl, EffectLedgerRepository ledger) {
        ParameterMasker masker = new ParameterMasker();
        return new ToolExecutionGateway(hitl,
                new PathGuard(List.of(System.getProperty("user.dir") + "/tmp")),
                new CommandGuard(List.of()), masker, new AuditLogger(masker), null, ledger);
    }

    private ToolCapabilityCard writeCard(String name) {
        return new ToolCapabilityCard(name, "1.0.0", ToolCapabilityCard.RiskLevel.LOW,
                ToolCapabilityCard.SideEffect.WRITE, false, 2_000,
                List.of("operator"), "{}", "{}");
    }

    private static final class FixedEffectLedger implements EffectLedgerRepository {
        private final ToolEffectLedgerEntry entry;

        private FixedEffectLedger(ToolEffectLedgerEntry entry) {
            this.entry = entry;
        }

        @Override
        public Optional<ToolEffectLedgerEntry> find(String runId, int stepNo, String toolName,
                                                     String toolVersion, String idempotencyKey,
                                                     String parameterHash) {
            return Optional.of(entry);
        }

        @Override
        public boolean reserve(ToolEffectLedgerEntry entry) {
            return false;
        }

        @Override
        public ToolEffectLedgerEntry complete(ToolEffectLedgerEntry entry) {
            return entry;
        }
    }

    private ToolCallback callback(String name, String schema) {
        return callback(name, schema, new AtomicInteger());
    }

    private ToolCallback callback(String name, String schema, AtomicInteger calls) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(name).inputSchema(schema).build();
            }

            @Override
            public String call(String toolInput) {
                calls.incrementAndGet();
                return "ok";
            }
        };
    }
}
