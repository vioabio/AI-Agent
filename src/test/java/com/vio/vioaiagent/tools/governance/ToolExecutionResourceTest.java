package com.vio.vioaiagent.tools.governance;

import com.vio.vioaiagent.run.EffectLedgerRepository;
import com.vio.vioaiagent.run.ToolEffectLedgerEntry;
import com.vio.vioaiagent.run.ToolEffectStatus;
import com.vio.vioaiagent.security.AuditLogger;
import com.vio.vioaiagent.security.CommandGuard;
import com.vio.vioaiagent.security.HitlManager;
import com.vio.vioaiagent.security.ParameterMasker;
import com.vio.vioaiagent.security.PathGuard;
import com.vio.vioaiagent.security.ToolAuditSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class ToolExecutionResourceTest {
    @Test
    void saturatedAndClosedGatewayReturnExplicitRejectionsWithoutCallbackOrRetry() throws Exception {
        ToolExecutionGateway gateway = gateway(new HitlManager(), null);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        gateway.wrapMcp(callback("boundedRead", () -> {
            calls.incrementAndGet(); started.countDown(); release.await(); return "ok";
        }), card("boundedRead", false, 5000));
        try {
            Future<ToolGovernanceResult> first = caller.submit(() ->
                    gateway.execute("boundedRead", "{}", context("first", false)));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            ToolGovernanceResult overloaded = gateway.execute("boundedRead", "{}", context("overflow", false));
            assertEquals(ToolGovernanceResult.Status.REJECTED, overloaded.status());
            assertTrue(overloaded.output().startsWith(BoundedToolExecutor.OVERLOADED));
            assertEquals(0, overloaded.retryCount());
            assertEquals(1, calls.get());
            assertEquals(1, gateway.executionSnapshot().largestPoolSize());
            release.countDown();
            assertEquals(ToolGovernanceResult.Status.EXECUTED, first.get(2, TimeUnit.SECONDS).status());
            gateway.shutdown();
            ToolGovernanceResult closed = gateway.execute("boundedRead", "{}", context("closed", false));
            assertEquals(ToolGovernanceResult.Status.REJECTED, closed.status());
            assertTrue(closed.output().startsWith(BoundedToolExecutor.SHUTDOWN));
            assertEquals(1, calls.get());
        } finally {
            release.countDown(); caller.shutdownNow(); gateway.shutdown();
        }
    }

    @Test
    void timeoutOfUncooperativeCallbackDoesNotStartOverlappingRetryOrUnboundedWorker() throws Exception {
        ToolExecutionGateway gateway = gateway(new HitlManager(), null);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch callbackFinished = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        gateway.wrapMcp(callback("uncooperativeRead", () -> {
            calls.incrementAndGet(); started.countDown();
            for (;;) {
                try { release.await(); break; } catch (InterruptedException ignored) { /* fixture ignores interruption */ }
            }
            callbackFinished.countDown();
            return "late result";
        }), card("uncooperativeRead", false, 1000));
        try {
            Future<ToolGovernanceResult> first = caller.submit(() ->
                    gateway.execute("uncooperativeRead", "{}", context("timeout", false)));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            ToolGovernanceResult timedOut = first.get(3, TimeUnit.SECONDS);
            assertEquals(ToolGovernanceResult.Status.FAILED, timedOut.status());
            assertTrue(timedOut.output().startsWith(BoundedToolExecutor.TIMEOUT));
            assertEquals(0, timedOut.retryCount());
            assertEquals(1, calls.get());
            ToolGovernanceResult next = gateway.execute("uncooperativeRead", "{}", context("still-busy", false));
            assertEquals(ToolGovernanceResult.Status.REJECTED, next.status());
            assertTrue(next.output().startsWith(BoundedToolExecutor.OVERLOADED));
            assertEquals(1, gateway.executionSnapshot().largestPoolSize());
            assertEquals(1, calls.get());
            release.countDown();
            assertTrue(callbackFinished.await(2, TimeUnit.SECONDS));
        } finally {
            release.countDown(); caller.shutdownNow(); gateway.shutdown();
        }
    }

    @Test
    void cancelledStartedWriteIsUnknownInLedgerAndCannotBeReplayed() throws Exception {
        HitlManager hitl = new HitlManager();
        CapturingLedger ledger = new CapturingLedger();
        ToolExecutionGateway gateway = gateway(hitl, ledger);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<ToolGovernanceResult> cancelled = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        gateway.wrapMcp(callback("uncertainWrite", () -> {
            calls.incrementAndGet(); started.countDown(); release.await(); return "ok";
        }), card("uncertainWrite", true, 5000));
        ToolExecutionContext context = context("uncertain-write", true);
        ToolGovernanceResult pending = gateway.execute("uncertainWrite", "{}", context);
        assertEquals(ToolGovernanceResult.Status.WAITING_APPROVAL, pending.status());
        hitl.decide(pending.approvalId(), context.userId(), context.runId(), true, "approved");
        Thread caller = new Thread(() -> {
            cancelled.set(gateway.execute("uncertainWrite", "{}", context));
            interruptPreserved.set(Thread.currentThread().isInterrupted());
        }, "cancelled-write-caller");
        try {
            caller.start();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(2000);
            assertFalse(caller.isAlive());
            assertNotNull(cancelled.get());
            assertEquals(ToolGovernanceResult.Status.FAILED, cancelled.get().status());
            assertTrue(cancelled.get().output().startsWith(BoundedToolExecutor.CANCELLED));
            assertTrue(interruptPreserved.get());
            assertEquals(ToolEffectStatus.UNKNOWN, ledger.row.get().status());
            ToolGovernanceResult replay = gateway.execute("uncertainWrite", "{}", context);
            assertEquals(ToolGovernanceResult.Status.REJECTED, replay.status());
            assertTrue(replay.output().contains("UNKNOWN"));
            assertEquals(1, calls.get());
        } finally {
            caller.interrupt(); release.countDown(); gateway.shutdown();
        }
    }

    @Test
    void timedOutWriteWithoutLedgerLeavesPermanentUnknownAndNeverOverlapsSameKey() throws Exception {
        HitlManager hitl = new HitlManager();
        // A spare worker ensures the rejection comes from effect safety, not resource saturation.
        ToolExecutionGateway gateway = gateway(hitl, null, ToolAuditSink.noop(),
                new ToolExecutionProperties(2, 0, 0));
        ExecutorService caller = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        gateway.wrapMcp(callback("tombstoneWrite", () -> {
            calls.incrementAndGet();
            started.countDown();
            for (;;) {
                try { release.await(); break; }
                catch (InterruptedException ignored) { /* external write cannot be cancelled */ }
            }
            finished.countDown();
            return "late write result";
        }), card("tombstoneWrite", true, 1000));
        ToolExecutionContext context = context("tombstone-write", true);
        ToolGovernanceResult pending = gateway.execute("tombstoneWrite", "{}", context);
        assertEquals(ToolGovernanceResult.Status.WAITING_APPROVAL, pending.status());
        hitl.decide(pending.approvalId(), context.userId(), context.runId(), true, "approved");
        try {
            Future<ToolGovernanceResult> first = caller.submit(() -> gateway.execute("tombstoneWrite", "{}", context));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            ToolGovernanceResult timeout = first.get(3, TimeUnit.SECONDS);
            assertEquals(ToolGovernanceResult.Status.FAILED, timeout.status());
            assertTrue(timeout.output().startsWith(BoundedToolExecutor.TIMEOUT));
            assertEquals(0, timeout.retryCount());
            ToolGovernanceResult overlappingReplay = gateway.execute("tombstoneWrite", "{}", context);
            assertEquals(ToolGovernanceResult.Status.REJECTED, overlappingReplay.status());
            assertTrue(overlappingReplay.output().contains("UNKNOWN"));
            assertEquals(1, calls.get());

            release.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            await().atMost(Duration.ofSeconds(2)).until(() -> gateway.executionSnapshot().activeCount() == 0);
            ToolGovernanceResult laterReplay = gateway.execute("tombstoneWrite", "{}", context);
            assertEquals(ToolGovernanceResult.Status.REJECTED, laterReplay.status());
            assertTrue(laterReplay.output().contains("UNKNOWN"));
            assertEquals(1, calls.get(), "a late callback result must not erase uncertainty or populate success cache");
        } finally {
            release.countDown(); caller.shutdownNow(); gateway.shutdown(); hitl.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completedWriteWithFailedAuditWithoutLedgerCannotBeExecutedOrDeduplicatedAgain(boolean failClosedAudit) {
        HitlManager hitl = spy(new HitlManager());
        AtomicInteger calls = new AtomicInteger();
        ToolAuditSink audit = new ToolAuditSink() {
            @Override public void record(com.vio.vioaiagent.security.AuditEntry entry) {
                if ("allow".equals(entry.outcome())) throw new IllegalStateException("audit unavailable");
            }

            @Override public boolean failClosedOnError() { return failClosedAudit; }
        };
        ToolExecutionGateway gateway = gateway(hitl, null, audit, new ToolExecutionProperties(1, 1, 0));
        gateway.wrapMcp(callback("auditUnknownWrite", () -> {
            calls.incrementAndGet();
            return "external write completed";
        }), card("auditUnknownWrite", true, 5000));
        ToolExecutionContext context = context("audit-unknown-write", true);
        try {
            ToolGovernanceResult pending = gateway.execute("auditUnknownWrite", "{}", context);
            assertEquals(ToolGovernanceResult.Status.WAITING_APPROVAL, pending.status());
            hitl.decide(pending.approvalId(), context.userId(), context.runId(), true, "approved");
            ToolGovernanceResult first = gateway.execute("auditUnknownWrite", "{}", context);
            assertEquals(ToolGovernanceResult.Status.FAILED, first.status());
            assertTrue(first.output().contains("审计服务不可用"));
            assertEquals(1, calls.get());
            // An expired approval would normally prompt again. The permanent tombstone must
            // reject replay before asking HITL to create a replacement approval.
            doReturn(HitlManager.ApprovalDecision.waiting("renewed-approval", Instant.now().plusSeconds(60), "renewed"))
                    .when(hitl).evaluate(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
            clearInvocations(hitl);
            ToolGovernanceResult replay = gateway.execute("auditUnknownWrite", "{}", context);
            assertEquals(ToolGovernanceResult.Status.REJECTED, replay.status());
            assertTrue(replay.output().contains("UNKNOWN"));
            assertEquals(1, calls.get(), "audit failure cannot populate a DEDUPLICATED success cache");
            verify(hitl, never()).evaluate(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
        } finally {
            gateway.shutdown(); hitl.shutdown();
        }
    }

    private ToolExecutionGateway gateway(HitlManager hitl, EffectLedgerRepository ledger) {
        return gateway(hitl, ledger, null, new ToolExecutionProperties(1, 0, 1000));
    }

    private ToolExecutionGateway gateway(HitlManager hitl, EffectLedgerRepository ledger,
                                         ToolAuditSink audit, ToolExecutionProperties properties) {
        ParameterMasker masker = new ParameterMasker();
        return new ToolExecutionGateway(hitl, new PathGuard(List.of(System.getProperty("user.dir") + "/tmp")),
                new CommandGuard(List.of()), masker, audit == null ? new AuditLogger(masker) : audit,
                null, ledger, false, properties);
    }

    private ToolExecutionContext context(String runId, boolean write) {
        return new ToolExecutionContext("u1", runId, Set.of(write ? "operator" : "trainer"),
                "trace", runId, null);
    }

    private ToolCapabilityCard card(String name, boolean write, long timeout) {
        return new ToolCapabilityCard(name, "1.0.0", ToolCapabilityCard.RiskLevel.LOW,
                write ? ToolCapabilityCard.SideEffect.WRITE : ToolCapabilityCard.SideEffect.NONE,
                !write, timeout, List.of(write ? "operator" : "trainer"), "{}", "{}");
    }

    private ToolCallback callback(String name, java.util.concurrent.Callable<String> body) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                try { return body.call(); } catch (Exception ex) { throw new RuntimeException(ex); }
            }
        };
    }

    private static final class CapturingLedger implements EffectLedgerRepository {
        private final AtomicReference<ToolEffectLedgerEntry> row = new AtomicReference<>();

        @Override
        public Optional<ToolEffectLedgerEntry> find(String runId, int stepNo, String toolName,
                String toolVersion, String idempotencyKey, String parameterHash) {
            return Optional.ofNullable(row.get());
        }

        @Override
        public boolean reserve(ToolEffectLedgerEntry entry) { return row.compareAndSet(null, entry); }

        @Override
        public ToolEffectLedgerEntry complete(ToolEffectLedgerEntry entry) { row.set(entry); return entry; }
    }
}
