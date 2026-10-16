package com.vio.vioaiagent.run;

import com.vio.vioaiagent.config.DurableRunProperties;
import com.vio.vioaiagent.config.RunAdmissionProperties;
import com.vio.vioaiagent.observability.AgentMetrics;
import com.vio.vioaiagent.security.HitlApproval;
import com.vio.vioaiagent.security.HitlAuditSink;
import com.vio.vioaiagent.security.HitlManager;
import com.vio.vioaiagent.security.InMemoryHitlApprovalRepository;
import com.vio.vioaiagent.tools.governance.ToolExecutionContextHolder;
import com.vio.vioaiagent.tools.governance.ToolGovernanceEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/** Approval pauses retain admission until resolution or the last physical worker exits. */
class PokemonAgentRunServiceApprovalAdmissionTest {

    @Test
    void waitingApprovalRetainsPrincipalAndTenantQuotaAfterWorkerExits() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (Fixture f = new Fixture(context -> {
            calls.incrementAndGet();
            emitApproval(context, "approval-waiting", Instant.now().plusSeconds(120));
            return "awaiting approval";
        })) {
            RunView waiting = f.service.createRun(command("owner", "waiting"));
            awaitApprovalIdle(f.service, f.events, waiting.runId());
            assertWaitingReservation(f.service);
            long lastEvent = f.events.lastEventId(waiting.runId());

            assertEquals("RUN_PRINCIPAL_LIMIT", assertThrows(RunAdmissionException.class,
                    () -> f.service.createRun(command("owner", "same-owner"))).code());
            assertEquals("RUN_TENANT_LIMIT", assertThrows(RunAdmissionException.class,
                    () -> f.service.createRun(command("other-owner", "same-tenant"))).code());
            assertEquals(1, calls.get());
            assertEquals(lastEvent, f.events.lastEventId(waiting.runId()));
            assertWaitingReservation(f.service);
        }
    }

    @ParameterizedTest
    @EnumSource(ReleaseAction.class)
    void rejectingCancellingOrDeletingIdleApprovalReleasesQuota(ReleaseAction action) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (Fixture f = new Fixture(context -> {
            if (calls.incrementAndGet() == 1) {
                emitApproval(context, "approval-release", Instant.now().plusSeconds(120));
            }
            return "completed";
        })) {
            RunView waiting = f.service.createRun(command("owner", "waiting"));
            awaitApprovalIdle(f.service, f.events, waiting.runId());

            switch (action) {
                case REJECT -> f.service.resolveApproval(waiting.runId(), "owner", false, "denied");
                case CANCEL -> f.service.cancel(waiting.runId(), "owner");
                case DELETE_CONVERSATION -> f.service.deleteConversation(waiting.conversationId(), "owner");
            }
            await(() -> f.service.runtimeSnapshot().reserved() == 0);
            assertIdle(f.service);
            if (action == ReleaseAction.DELETE_CONVERSATION) {
                assertTrue(f.service.findRun(waiting.runId(), "owner").isEmpty());
            } else {
                AgentEvent terminal = RunTestAwait.awaitTerminalEvent(f.events, waiting.runId());
                assertEquals(action == ReleaseAction.REJECT
                        ? AgentEventType.RUN_FAILED : AgentEventType.RUN_CANCELLED, terminal.type());
                assertEquals(1, f.events.findAfter(waiting.runId(), 0).stream()
                        .filter(event -> event.type().isTerminal()).count());
            }

            RunView next = f.service.createRun(command("owner", "after-release"));
            assertEquals(AgentEventType.RUN_COMPLETED,
                    RunTestAwait.awaitTerminalEvent(f.events, next.runId()).type());
            awaitIdle(f.service);
            assertIdle(f.service);
            assertEquals(2, calls.get());
        }
    }

    @Test
    void approvalResumeReusesExistingPermitUntilResumedWorkerCompletes() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch resumed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Fixture f = new Fixture(context -> {
            if (calls.incrementAndGet() == 1) {
                emitApproval(context, "approval-resume", Instant.now().plusSeconds(120));
                return "awaiting approval";
            }
            resumed.countDown();
            release.await();
            return "approved answer";
        })) {
            RunView waiting = f.service.createRun(command("owner", "waiting"));
            awaitApprovalIdle(f.service, f.events, waiting.runId());
            f.service.resolveApproval(waiting.runId(), "owner", true, "approved");
            assertTrue(resumed.await(5, TimeUnit.SECONDS), "resume must not reacquire the exhausted quota");
            assertEquals(1, f.service.runtimeSnapshot().reserved());
            assertEquals(1, f.service.runtimeSnapshot().tasks());
            assertEquals(1, f.service.runtimeSnapshot().principalScopes());
            assertEquals(1, f.service.runtimeSnapshot().tenantScopes());
            assertEquals("RUN_PRINCIPAL_LIMIT", assertThrows(RunAdmissionException.class,
                    () -> f.service.createRun(command("owner", "during-resume"))).code());

            release.countDown();
            assertEquals(AgentEventType.RUN_COMPLETED,
                    RunTestAwait.awaitTerminalEvent(f.events, waiting.runId()).type());
            awaitIdle(f.service);
            assertIdle(f.service);
            assertEquals(2, calls.get());
            assertEquals(1, f.events.findAfter(waiting.runId(), 0).stream()
                    .filter(event -> event.type().isTerminal()).count());
            RunView next = f.service.createRun(command("owner", "after-resume"));
            RunTestAwait.awaitTerminalEvent(f.events, next.runId());
        } finally {
            release.countDown();
        }
    }

    @ParameterizedTest
    @EnumSource(value = ReleaseAction.class, names = {"REJECT", "CANCEL"})
    void resolutionEventFailureStillReleasesIdleApprovalReservation(ReleaseAction action) throws Exception {
        try (Fixture f = new Fixture(context -> {
            emitApproval(context, "approval-event-failure", Instant.now().plusSeconds(120));
            return "awaiting approval";
        })) {
            RunView waiting = f.service.createRun(command("owner", "waiting"));
            awaitApprovalIdle(f.service, f.events, waiting.runId());
            AgentEventType failingType = action == ReleaseAction.REJECT
                    ? AgentEventType.APPROVAL_RESOLVED : AgentEventType.RUN_CANCELLED;
            doAnswer(invocation -> {
                AgentEvent event = invocation.getArgument(0);
                if (event.type() == failingType) throw new IllegalStateException("event store unavailable");
                return invocation.callRealMethod();
            }).when(f.events).appendNext(any(AgentEvent.class));

            assertThrows(IllegalStateException.class, () -> {
                if (action == ReleaseAction.REJECT) {
                    f.service.resolveApproval(waiting.runId(), "owner", false, "denied");
                } else {
                    f.service.cancel(waiting.runId(), "owner");
                }
            });
            assertEquals(action == ReleaseAction.REJECT ? AgentRunStatus.FAILED : AgentRunStatus.CANCELLED,
                    f.runs.findById(waiting.runId()).orElseThrow().status());
            assertIdle(f.service);
            RunView next = f.service.createRun(command("owner", "after-event-failure"));
            awaitApprovalIdle(f.service, f.events, next.runId());
            assertWaitingReservation(f.service);
        }
    }

    @Test
    void shutdownPreservesPendingApprovalFactsAndRecoveryRestoresReservationWithoutExecution() throws Exception {
        DurableState state = new DurableState();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> approvalId = new AtomicReference<>();
        HitlManager hitl = newHitl(state);
        RunExecutor executor = context -> {
            calls.incrementAndGet();
            HitlManager.ApprovalDecision approval = requestApproval(hitl, context);
            approvalId.set(approval.approvalId());
            emitApproval(context, approval.approvalId(), approval.expiresAt());
            return "awaiting approval";
        };
        PokemonAgentRunService first = newDurableService(state, hitl, executor);
        PokemonAgentRunService recovered = null;
        try {
            first.recoverInFlightRuns();
            RunView waiting = first.createRun(command("owner", "waiting"));
            awaitApprovalIdle(first, state.events, waiting.runId());
            AgentCheckpoint checkpoint = state.checkpoints.findLatest(waiting.runId()).orElseThrow();
            List<AgentEvent> eventFacts = state.events.findAfter(waiting.runId(), 0);
            HitlApproval approvalFact = state.approvals.find(approvalId.get()).orElseThrow();

            first.shutdown();
            assertIdle(first);
            assertFalse(first.runtimeSnapshot().accepting());
            assertEquals(AgentRunStatus.WAITING_APPROVAL, state.runs.findById(waiting.runId()).orElseThrow().status());
            assertEquals(HitlApproval.Decision.PENDING, state.approvals.find(approvalId.get()).orElseThrow().decision());
            assertEquals(approvalFact, state.approvals.find(approvalId.get()).orElseThrow());
            assertEquals(checkpoint, state.checkpoints.findLatest(waiting.runId()).orElseThrow());
            assertEquals(eventFacts, state.events.findAfter(waiting.runId(), 0));
            assertFalse(state.events.hasTerminalEvent(waiting.runId()));

            recovered = newDurableService(state, hitl, executor);
            recovered.recoverInFlightRuns();
            assertWaitingReservation(recovered);
            assertEquals(AgentRunStatus.WAITING_APPROVAL, recovered.findRun(waiting.runId(), "owner").orElseThrow().status());
            assertEquals(1, calls.get(), "recovery must not replay the model or tool while approval is pending");
            assertEquals(eventFacts, state.events.findAfter(waiting.runId(), 0));
        } finally {
            if (recovered != null) recovered.shutdown();
            first.shutdown();
            hitl.shutdown();
        }
    }

    @Test
    void shutdownWaitingWorkerReleasesLocalReservationOnlyAfterPhysicalExit() throws Exception {
        DurableState state = new DurableState();
        HitlManager hitl = newHitl(state);
        AtomicReference<String> approvalId = new AtomicReference<>();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        PokemonAgentRunService service = newDurableService(state, hitl, context -> {
            HitlManager.ApprovalDecision approval = requestApproval(hitl, context);
            approvalId.set(approval.approvalId());
            emitApproval(context, approval.approvalId(), approval.expiresAt());
            uninterruptibly(release, interrupted);
            return "awaiting approval";
        });
        try {
            service.recoverInFlightRuns();
            RunView waiting = service.createRun(command("owner", "waiting"));
            RunTestAwait.awaitEvent(state.events, waiting.runId(), AgentEventType.APPROVAL_REQUIRED);
            assertEquals(1, service.runtimeSnapshot().tasks());
            service.shutdown();
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertEquals(1, service.runtimeSnapshot().reserved(), "the old callback still occupies its worker");
            assertEquals(1, service.runtimeSnapshot().tasks());
            assertFalse(service.runtimeSnapshot().accepting());
            assertEquals(AgentRunStatus.WAITING_APPROVAL, state.runs.findById(waiting.runId()).orElseThrow().status());
            assertEquals(HitlApproval.Decision.PENDING, state.approvals.find(approvalId.get()).orElseThrow().decision());
            assertFalse(state.events.hasTerminalEvent(waiting.runId()));

            release.countDown();
            awaitIdle(service);
            assertIdle(service);
            assertEquals(AgentRunStatus.WAITING_APPROVAL, state.runs.findById(waiting.runId()).orElseThrow().status());
            assertEquals(HitlApproval.Decision.PENDING, state.approvals.find(approvalId.get()).orElseThrow().decision());
            assertTrue(state.checkpoints.findLatest(waiting.runId()).isPresent());
            assertFalse(state.events.hasTerminalEvent(waiting.runId()));
        } finally {
            release.countDown();
            service.shutdown();
            hitl.shutdown();
        }
    }

    @Test
    void shutdownCooperativeWaitingWorkerPreservesPendingFactsAfterInterruptedException() throws Exception {
        DurableState state = new DurableState();
        HitlManager hitl = newHitl(state);
        AtomicReference<String> approvalId = new AtomicReference<>();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        PokemonAgentRunService service = newDurableService(state, hitl, context -> {
            HitlManager.ApprovalDecision approval = requestApproval(hitl, context);
            approvalId.set(approval.approvalId());
            emitApproval(context, approval.approvalId(), approval.expiresAt());
            try {
                release.await();
            } catch (InterruptedException ex) {
                interrupted.countDown();
                throw ex;
            }
            return "awaiting approval";
        });
        try {
            service.recoverInFlightRuns();
            RunView waiting = service.createRun(command("owner", "cooperative-waiting"));
            RunTestAwait.awaitEvent(state.events, waiting.runId(), AgentEventType.APPROVAL_REQUIRED);
            assertEquals(1, service.runtimeSnapshot().tasks());

            service.shutdown();
            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "shutdown must interrupt the waiting callback");
            awaitIdle(service);
            assertIdle(service);
            assertFalse(service.runtimeSnapshot().accepting());
            assertEquals(AgentRunStatus.WAITING_APPROVAL, state.runs.findById(waiting.runId()).orElseThrow().status());
            assertEquals(HitlApproval.Decision.PENDING, state.approvals.find(approvalId.get()).orElseThrow().decision());
            assertTrue(state.checkpoints.findLatest(waiting.runId()).isPresent());
            assertFalse(state.events.hasTerminalEvent(waiting.runId()),
                    "cooperative shutdown must not turn a durable pending approval into cancellation");
        } finally {
            release.countDown();
            service.shutdown();
            hitl.shutdown();
        }
    }

    private static HitlManager.ApprovalDecision requestApproval(HitlManager hitl, AgentRunContext context) {
        HitlManager.ApprovalDecision approval = hitl.evaluate(context.run().userId(), context.run().id(),
                "write_file", "1.0.0", "digest-" + context.run().id(), true);
        assertTrue(approval.waiting());
        return approval;
    }

    private static void emitApproval(AgentRunContext context, String approvalId, Instant expiresAt) {
        ToolExecutionContextHolder.current().eventSink().accept(new ToolGovernanceEvent(
                "approval.required", context.run().id(), "write_file", "governance-" + approvalId,
                Instant.now(), Map.of("approvalId", approvalId, "expiresAt", expiresAt.toString(), "riskLevel", "MEDIUM")));
    }

    private static void awaitApprovalIdle(PokemonAgentRunService service, EventRepository events, String runId) throws Exception {
        RunTestAwait.awaitEvent(events, runId, AgentEventType.APPROVAL_REQUIRED);
        await(() -> service.runtimeSnapshot().tasks() == 0);
    }

    private static void assertWaitingReservation(PokemonAgentRunService service) {
        PokemonAgentRunService.RuntimeSnapshot state = service.runtimeSnapshot();
        assertEquals(1, state.reserved());
        assertEquals(0, state.tasks());
        assertEquals(1, state.principalScopes());
        assertEquals(1, state.tenantScopes());
        assertEquals(1, state.deadlineTimers());
        assertEquals(0, state.queueTimers());
    }

    private static void assertIdle(PokemonAgentRunService service) {
        PokemonAgentRunService.RuntimeSnapshot state = service.runtimeSnapshot();
        assertEquals(0, state.reserved());
        assertEquals(0, state.tasks());
        assertEquals(0, state.principalScopes());
        assertEquals(0, state.tenantScopes());
        assertEquals(0, state.deadlineTimers());
        assertEquals(0, state.queueTimers());
    }

    private static void awaitIdle(PokemonAgentRunService service) throws InterruptedException {
        // Task removal happens before reservation cleanup inside finishTask.
        // Await both facts instead of observing that narrow intermediate snapshot.
        await(() -> {
            PokemonAgentRunService.RuntimeSnapshot state = service.runtimeSnapshot();
            return state.tasks() == 0 && state.reserved() == 0;
        });
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "timed out waiting for approval lifecycle condition");
    }

    private static void uninterruptibly(CountDownLatch release, CountDownLatch interrupted) {
        boolean restore = false;
        while (release.getCount() != 0) {
            try { release.await(); }
            catch (InterruptedException ex) { restore = true; interrupted.countDown(); }
        }
        if (restore) Thread.currentThread().interrupt();
    }

    private static CreateRunCommand command(String owner, String key) {
        return new CreateRunCommand(owner, "session-" + owner, null, key, "sv-1", "singles",
                "rules-1", "zh-CN", "trace-" + key, key, Duration.ofMinutes(2));
    }

    private static RunAdmissionProperties limits() {
        RunAdmissionProperties properties = new RunAdmissionProperties();
        properties.setThreadCount(1);
        properties.setQueueCapacity(1);
        properties.setMaxPerPrincipal(1);
        properties.setMaxPerTenant(1);
        return properties;
    }

    private static HitlManager newHitl(DurableState state) {
        HitlManager hitl = new HitlManager(HitlAuditSink.noop());
        hitl.setApprovalRepository(state.approvals);
        return hitl;
    }

    private static PokemonAgentRunService newDurableService(DurableState state, HitlManager hitl, RunExecutor executor) {
        DurableRunProperties durable = new DurableRunProperties();
        durable.setDurableResume(true);
        DefaultListableBeanFactory providers = new DefaultListableBeanFactory();
        providers.registerSingleton("executor", executor);
        providers.registerSingleton("hitl", hitl);
        providers.registerSingleton("checkpoints", state.checkpoints);
        providers.registerSingleton("durable", durable);
        providers.registerSingleton("admission", limits());
        return new PokemonAgentRunService(state.runs, state.events, state.conversations, state.messages,
                providers.getBeanProvider(RunExecutor.class), providers.getBeanProvider(AgentMetrics.class),
                providers.getBeanProvider(HitlManager.class), providers.getBeanProvider(RunOutboxRepository.class),
                providers.getBeanProvider(CheckpointRepository.class), providers.getBeanProvider(DurableRunProperties.class),
                providers.getBeanProvider(RunAdmissionProperties.class));
    }

    private enum ReleaseAction { REJECT, CANCEL, DELETE_CONVERSATION }

    private static final class Fixture implements AutoCloseable {
        final InMemoryRunRepository runs = new InMemoryRunRepository();
        final InMemoryEventRepository events = spy(new InMemoryEventRepository());
        final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        final PokemonAgentRunService service;

        Fixture(RunExecutor executor) {
            RunAdmissionProperties properties = limits();
            ThreadPoolExecutor worker = PokemonAgentRunService.newWorker(properties);
            scheduler.setRemoveOnCancelPolicy(true);
            service = new PokemonAgentRunService(runs, events, new InMemoryConversationRepository(),
                    new InMemoryChatMessageRepository(), executor, worker, scheduler, properties);
        }

        @Override public void close() { service.shutdown(); }
    }

    private static final class DurableState {
        final InMemoryRunRepository runs = new InMemoryRunRepository();
        final InMemoryEventRepository events = new InMemoryEventRepository();
        final InMemoryConversationRepository conversations = new InMemoryConversationRepository();
        final InMemoryChatMessageRepository messages = new InMemoryChatMessageRepository();
        final InMemoryDurableRunStateRepository checkpoints = new InMemoryDurableRunStateRepository();
        // A shared in-memory substitute exercises durable HITL CAS and restart paths.
        final InMemoryHitlApprovalRepository approvals = new InMemoryHitlApprovalRepository();
    }
}
