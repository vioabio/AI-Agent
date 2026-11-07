package com.vio.vioaiagent.run;

import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.config.DurableRunProperties;
import com.vio.vioaiagent.security.HitlApproval;
import com.vio.vioaiagent.security.HitlAuditSink;
import com.vio.vioaiagent.security.HitlManager;
import com.vio.vioaiagent.security.InMemoryHitlApprovalRepository;
import com.vio.vioaiagent.tools.governance.ToolExecutionContext;
import com.vio.vioaiagent.tools.governance.ToolExecutionContextHolder;
import com.vio.vioaiagent.tools.governance.ToolGovernanceEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** A stale physical worker and its asynchronous callbacks must never regain write authority. */
class PokemonAgentRunServiceLeaseFencingTest {

    @ParameterizedTest
    @EnumSource(value = HeartbeatFailure.class, names = {"EMPTY", "THROW"})
    void heartbeatLossRevokesWritesEvenWhileStoredLeaseStillBelongsToOldWorker(HeartbeatFailure failure)
            throws Exception {
        BlockedExecutor executor = new BlockedExecutor();
        try (Fixture f = new Fixture(executor, executor.release)) {
            RunView view = f.service.createRun(command("heartbeat-" + failure));
            executor.awaitEntered();
            StoredFacts before = facts(f, view);
            RunLease lease = f.durable.currentLease(view.runId()).orElseThrow();

            f.durable.failure = failure;
            f.scheduler.fireHeartbeat();
            assertTrue(executor.interrupted.await(5, TimeUnit.SECONDS), "lease loss must interrupt the worker");
            assertEquals(lease, f.durable.currentLease(view.runId()).orElseThrow(),
                    "the fixture intentionally leaves the old database lease valid");
            assertTrue(f.durable.withLease(view.runId(), lease.owner(), lease.token(), () -> true),
                    "repository ownership alone still permits this token; local revocation must fence it");
            assertOccupiedUntilPhysicalExit(f);

            // The still-valid repository lease must not let this revoked execution commit.
            executor.release.countDown();
            f.worker.awaitPhysicalExit();

            assertNoWorkerWrites(f, view, before);
            assertIdle(f);
        }
    }

    @Test
    void replacementLeaseRejectsOldCommitWithoutWaitingForHeartbeat() throws Exception {
        BlockedExecutor executor = new BlockedExecutor();
        try (Fixture f = new Fixture(executor, executor.release)) {
            RunView view = f.service.createRun(command("takeover"));
            executor.awaitEntered();
            StoredFacts before = facts(f, view);
            RunLease replacement = replaceLease(f, view.runId());
            assertOccupiedUntilPhysicalExit(f);

            executor.release.countDown();
            f.worker.awaitPhysicalExit();

            assertNoWorkerWrites(f, view, before);
            assertEquals(replacement, f.durable.currentLease(view.runId()).orElseThrow(),
                    "old worker cleanup must not release the replacement owner's lease");
            assertIdle(f);
        }
    }

    @Test
    void takeoverWhileWaitingApprovalReleasesLocalResourcesAndPreservesPendingFacts() throws Exception {
        InMemoryHitlApprovalRepository approvals = new InMemoryHitlApprovalRepository();
        HitlManager hitl = new HitlManager(HitlAuditSink.noop());
        hitl.setApprovalRepository(approvals);
        AtomicReference<String> approvalId = new AtomicReference<>();
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RunExecutor executor = context -> {
            HitlManager.ApprovalDecision approval = hitl.evaluate(context.run().userId(), context.run().id(),
                    "write_file", "1.0.0", "digest-" + context.run().id(), true);
            assertTrue(approval.waiting());
            approvalId.set(approval.approvalId());
            ToolExecutionContextHolder.current().eventSink().accept(new ToolGovernanceEvent("approval.required",
                    context.run().id(), "write_file", "approval-before-takeover", Instant.now(),
                    Map.of("approvalId", approval.approvalId(), "riskLevel", "MEDIUM",
                            "expiresAt", approval.expiresAt().toString())));
            waiting.countDown();
            awaitIgnoringInterrupts(release, new CountDownLatch(1));
            return "old approval executor answer";
        };
        try (Fixture f = new Fixture(executor, hitl, release)) {
            RunView view = f.service.createRun(command("approval-takeover"));
            assertTrue(waiting.await(5, TimeUnit.SECONDS), "executor must publish approval before takeover");
            StoredFacts before = facts(f, view);
            HitlApproval pending = approvals.find(approvalId.get()).orElseThrow();
            assertEquals(AgentRunStatus.WAITING_APPROVAL, before.status());
            assertEquals(HitlApproval.Decision.PENDING, pending.decision());
            RunLease replacement = replaceLease(f, view.runId());
            assertOccupiedUntilPhysicalExit(f);

            // Returning now attempts commit-answer checkpoint under the superseded lease.
            release.countDown();
            f.worker.awaitPhysicalExit();

            assertEquals(before, facts(f, view), "lease loss must preserve waiting Run and approval event/checkpoint facts");
            assertEquals(pending, approvals.find(approvalId.get()).orElseThrow(), "PENDING approval must remain unchanged");
            assertEquals(AgentRunStatus.WAITING_APPROVAL, f.runs.findById(view.runId()).orElseThrow().status());
            assertFalse(f.events.hasTerminalEvent(view.runId()), "old worker must not publish FAILED or CANCELLED");
            assertTrue(f.projections.findByRunId(view.runId()).isEmpty());
            assertEquals(replacement, f.durable.currentLease(view.runId()).orElseThrow());
            assertIdle(f);
        } finally {
            release.countDown();
            hitl.shutdown();
        }
    }

    @Test
    void asynchronousToolEventFromRevokedWorkerCannotPublishFacts() throws Exception {
        BlockedExecutor executor = new BlockedExecutor();
        try (Fixture f = new Fixture(executor, executor.release)) {
            RunView view = f.service.createRun(command("late-tool-event"));
            executor.awaitEntered();
            StoredFacts before = facts(f, view);
            f.durable.failure = HeartbeatFailure.EMPTY;
            f.scheduler.fireHeartbeat();
            assertTrue(executor.interrupted.await(5, TimeUnit.SECONDS));

            assertInstanceOf(RunLeaseLostException.class, invokeOldToolSink(executor.toolContext.get()),
                    "an external callback thread must use the captured execution lease");
            assertNoWorkerWrites(f, view, before);
            assertOccupiedUntilPhysicalExit(f);

            executor.release.countDown();
            f.worker.awaitPhysicalExit();
            assertNoWorkerWrites(f, view, before);
            assertIdle(f);
        }
    }

    @Test
    void oldToolSinkCannotBorrowNewExecutionLeaseAfterApprovalResume() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<ToolExecutionContext> oldContext = new AtomicReference<>();
        CountDownLatch resumed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RunExecutor executor = context -> {
            if (calls.incrementAndGet() == 1) {
                ToolExecutionContext tool = ToolExecutionContextHolder.current();
                oldContext.set(tool);
                tool.eventSink().accept(new ToolGovernanceEvent("approval.required", context.run().id(),
                        "fixture_tool", "approval-first-execution", Instant.now(),
                        Map.of("approvalId", "lease-fence-approval", "riskLevel", "MEDIUM",
                                "expiresAt", Instant.now().plusSeconds(120).toString())));
                return "waiting for approval";
            }
            resumed.countDown();
            awaitIgnoringInterrupts(release, new CountDownLatch(1));
            return "approved answer";
        };
        try (Fixture f = new Fixture(executor, release)) {
            RunView view = f.service.createRun(command("approval-new-execution"));
            f.worker.awaitPhysicalExit();
            assertEquals(AgentRunStatus.WAITING_APPROVAL, f.runs.findById(view.runId()).orElseThrow().status());
            assertEquals(1, f.service.runtimeSnapshot().reserved());

            f.service.resolveApproval(view.runId(), "lease-owner", true, "approved");
            assertTrue(resumed.await(5, TimeUnit.SECONDS), "approval must start a new execution");
            RunLease resumedLease = f.durable.currentLease(view.runId()).orElseThrow();
            StoredFacts before = facts(f, view);

            assertInstanceOf(RunLeaseLostException.class, invokeOldToolSink(oldContext.get()),
                    "the old sink must not look up the current lease for the same Run");
            assertNoWorkerWrites(f, view, before);
            assertEquals(resumedLease, f.durable.currentLease(view.runId()).orElseThrow());
            assertOccupiedUntilPhysicalExit(f);

            release.countDown();
            f.worker.awaitPhysicalExit();
            assertEquals(2, calls.get());
            assertCompleted(f, view, "approved answer");
            assertIdle(f);
        }
    }

    @Test
    void currentOwnerCanPublishAnswerAndCompleteNormally() throws Exception {
        BlockedExecutor executor = new BlockedExecutor();
        try (Fixture f = new Fixture(executor, executor.release)) {
            RunView view = f.service.createRun(command("valid-owner"));
            executor.awaitEntered();
            f.scheduler.fireHeartbeat();
            assertOccupiedUntilPhysicalExit(f);

            executor.release.countDown();
            f.worker.awaitPhysicalExit();

            assertCompleted(f, view, "late answer");
            assertTrue(f.durable.currentLease(view.runId()).isEmpty());
            assertIdle(f);
        }
    }

    @Test
    void cancellationRevokesPhysicalWorkerBeforeItCanWriteLateCheckpoint() throws Exception {
        BlockedExecutor executor = new BlockedExecutor();
        try (Fixture f = new Fixture(executor, executor.release)) {
            RunView view = f.service.createRun(command("cancel-late-checkpoint"));
            executor.awaitEntered();

            CancelResult result = f.service.cancel(view.runId(), "lease-owner", null, "user_cancelled");
            assertEquals(AgentRunStatus.CANCELLED, result.status());
            StoredFacts afterCancel = facts(f, view);
            assertEquals(AgentRunStatus.CANCELLED, afterCancel.status());
            assertTrue(executor.interrupted.await(5, TimeUnit.SECONDS));

            // The executor deliberately ignores the interrupt and returns later.
            executor.release.countDown();
            f.worker.awaitPhysicalExit();

            assertEquals(afterCancel, facts(f, view));
            assertTrue(afterCancel.checkpoints().stream()
                    .noneMatch(checkpoint -> checkpoint.stepNo() == 1 || checkpoint.stepNo() == 2));
            assertTrue(f.projections.findByRunId(view.runId()).isEmpty());
            assertTrue(f.messages.findByConversationForUser(view.conversationId(), "lease-owner")
                    .stream().noneMatch(message -> "assistant".equals(message.role())));
            assertIdle(f);
        }
    }

    @Test
    void timeoutRevokesPhysicalWorkerAfterTimedOutFactIsCommitted() throws Exception {
        BlockedExecutor executor = new BlockedExecutor();
        try (Fixture f = new Fixture(executor, executor.release)) {
            CreateRunCommand command = new CreateRunCommand("lease-owner", "session-timeout", null,
                    "timeout-late-checkpoint", "sv-1", "singles", "rules-1", "zh-CN",
                    "trace-timeout", "timeout-late-checkpoint", Duration.ofMillis(250));
            RunView view = f.service.createRun(command);
            executor.awaitEntered();

            AgentEvent timedOut = RunTestAwait.awaitTerminalEvent(f.events, view.runId(), Duration.ofSeconds(5));
            assertEquals(AgentEventType.RUN_TIMED_OUT, timedOut.type());
            StoredFacts afterTimeout = facts(f, view);
            assertEquals(AgentRunStatus.TIMED_OUT, afterTimeout.status());
            assertTrue(executor.interrupted.await(5, TimeUnit.SECONDS));

            executor.release.countDown();
            f.worker.awaitPhysicalExit();

            assertEquals(afterTimeout, facts(f, view));
            assertTrue(afterTimeout.checkpoints().stream()
                    .noneMatch(checkpoint -> checkpoint.stepNo() == 1 || checkpoint.stepNo() == 2));
            assertTrue(f.projections.findByRunId(view.runId()).isEmpty());
            assertIdle(f);
        }
    }

    @Test
    void shutdownRevokesIgnoringInterruptWorkerAfterCancellationFact() throws Exception {
        BlockedExecutor executor = new BlockedExecutor();
        Fixture f = new Fixture(executor, executor.release);
        try {
            RunView view = f.service.createRun(command("shutdown-late-checkpoint"));
            executor.awaitEntered();

            f.service.shutdown();
            StoredFacts afterShutdown = facts(f, view);
            assertEquals(AgentRunStatus.CANCELLED, afterShutdown.status());
            assertTrue(executor.interrupted.await(5, TimeUnit.SECONDS));

            executor.release.countDown();
            f.worker.awaitPhysicalExit();
            assertEquals(afterShutdown, facts(f, view));
            assertTrue(afterShutdown.checkpoints().stream()
                    .noneMatch(checkpoint -> checkpoint.stepNo() == 1 || checkpoint.stepNo() == 2));
            assertTrue(f.projections.findByRunId(view.runId()).isEmpty());
        } finally {
            f.close();
        }
    }

    private static CreateRunCommand command(String key) {
        return new CreateRunCommand("lease-owner", "session-" + key, null, key,
                "sv-1", "singles", "rules-1", "zh-CN", "trace-" + key, key, Duration.ofMinutes(2));
    }

    private static RunLease replaceLease(Fixture f, String runId) {
        RunLease previous = f.durable.currentLease(runId).orElseThrow();
        assertTrue(f.durable.releaseLease(runId, previous.owner(), previous.token()));
        return f.durable.acquireLease(runId, "replacement-owner", "replacement-token", Duration.ofMinutes(2))
                .orElseThrow();
    }

    private static StoredFacts facts(Fixture f, RunView view) {
        AgentRun run = f.runs.findById(view.runId()).orElseThrow();
        return new StoredFacts(f.events.findAfter(view.runId(), 0), f.durable.findByRun(view.runId()),
                f.messages.findByConversationForUser(view.conversationId(), "lease-owner"),
                run.status(), run.version());
    }

    private static void assertNoWorkerWrites(Fixture f, RunView view, StoredFacts before) {
        assertEquals(before, facts(f, view), "stale execution must not mutate Run, events, checkpoints or messages");
        assertTrue(f.projections.findByRunId(view.runId()).isEmpty(), "stale execution must not save a projection");
        assertFalse(f.events.hasTerminalEvent(view.runId()), "lease loss is not an authority to commit a terminal event");
        assertEquals(AgentRunStatus.RUNNING, f.runs.findById(view.runId()).orElseThrow().status());
    }

    private static void assertOccupiedUntilPhysicalExit(Fixture f) {
        PokemonAgentRunService.RuntimeSnapshot state = f.service.runtimeSnapshot();
        assertEquals(1, state.reserved(), "Future cancellation must not release the live physical worker's permit");
        assertEquals(1, state.tasks(), "the task stays registered while its callback ignores interruption");
        assertEquals(1, f.worker.getActiveCount());
    }

    private static void assertIdle(Fixture f) {
        PokemonAgentRunService.RuntimeSnapshot state = f.service.runtimeSnapshot();
        assertEquals(0, state.reserved());
        assertEquals(0, state.tasks());
        assertEquals(0, state.deadlineTimers());
        assertEquals(0, state.queueTimers());
        assertEquals(0, state.principalScopes());
        assertEquals(0, state.tenantScopes());
        assertTrue(f.worker.getQueue().isEmpty());
    }

    private static void assertCompleted(Fixture f, RunView view, String expectedAnswer) {
        assertEquals(AgentRunStatus.COMPLETED, f.runs.findById(view.runId()).orElseThrow().status());
        assertTrue(f.projections.findByRunId(view.runId()).isPresent());
        assertEquals(List.of(expectedAnswer), f.messages.findByConversationForUser(view.conversationId(), "lease-owner")
                .stream().filter(message -> "assistant".equals(message.role())).map(ChatMessage::content).toList());
        List<AgentEvent> stream = f.events.findAfter(view.runId(), 0);
        assertEquals(1, stream.stream().filter(event -> event.type().isTerminal()).count());
        assertEquals(AgentEventType.RUN_COMPLETED, stream.get(stream.size() - 1).type());
        assertTrue(f.durable.findByRun(view.runId()).stream()
                .anyMatch(checkpoint -> checkpoint.stepNo() == 2 && "finished".equals(checkpoint.nextAction())));
    }

    private static Throwable invokeOldToolSink(ToolExecutionContext context) throws Exception {
        assertNotNull(context);
        ExecutorService callback = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "lease-fence-callback");
            thread.setDaemon(true);
            return thread;
        });
        try {
            return callback.submit(() -> {
                try {
                    context.eventSink().accept(new ToolGovernanceEvent("tool.completed", context.runId(),
                            "fixture_tool", "old-async-event", Instant.now(), Map.of("result", "late result")));
                    return (Throwable) null;
                } catch (RuntimeException ex) {
                    return ex;
                } finally {
                    ToolExecutionContextHolder.clear();
                    RequestContext.clear();
                }
            }).get(5, TimeUnit.SECONDS);
        } finally {
            callback.shutdownNow();
        }
    }

    private static void awaitIgnoringInterrupts(CountDownLatch release, CountDownLatch interrupted) {
        while (release.getCount() != 0) {
            try {
                release.await();
            } catch (InterruptedException ignored) {
                interrupted.countDown();
            }
        }
    }

    private record StoredFacts(List<AgentEvent> events, List<AgentCheckpoint> checkpoints,
                               List<ChatMessage> messages, AgentRunStatus status, long version) { }

    private enum HeartbeatFailure { NONE, EMPTY, THROW }

    private static final class FaultyDurable extends InMemoryDurableRunStateRepository {
        volatile HeartbeatFailure failure = HeartbeatFailure.NONE;

        @Override
        public Optional<RunLease> heartbeat(String runId, String owner, String token, Duration ttl) {
            return switch (failure) {
                case EMPTY -> Optional.empty();
                case THROW -> throw new IllegalStateException("fixture heartbeat store unavailable");
                case NONE -> super.heartbeat(runId, owner, token, ttl);
            };
        }
    }

    private static final class BlockedExecutor implements RunExecutor {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        final AtomicReference<ToolExecutionContext> toolContext = new AtomicReference<>();

        @Override
        public String execute(AgentRunContext context) {
            toolContext.set(ToolExecutionContextHolder.current());
            entered.countDown();
            awaitIgnoringInterrupts(release, interrupted);
            return "late answer";
        }

        void awaitEntered() throws InterruptedException {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "fixture executor did not start");
        }
    }

    private static final class ControlledScheduler extends ScheduledThreadPoolExecutor {
        private final AtomicReference<DormantHeartbeat> heartbeat = new AtomicReference<>();

        ControlledScheduler() {
            super(1, runnable -> {
                Thread thread = new Thread(runnable, "lease-fence-timer");
                thread.setDaemon(true);
                return thread;
            });
            setRemoveOnCancelPolicy(true);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            DormantHeartbeat next = new DormantHeartbeat(command);
            heartbeat.set(next);
            return next;
        }

        void fireHeartbeat() {
            DormantHeartbeat task = heartbeat.get();
            assertNotNull(task, "worker has not registered a heartbeat");
            assertFalse(task.isCancelled());
            task.command.run();
        }
    }

    private static final class DormantHeartbeat extends FutureTask<Void> implements ScheduledFuture<Void> {
        final Runnable command;

        DormantHeartbeat(Runnable command) {
            super(command, null);
            this.command = command;
        }

        @Override public long getDelay(TimeUnit unit) { return unit.convert(Long.MAX_VALUE, TimeUnit.NANOSECONDS); }
        @Override public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }
    }

    private static final class ObservedWorker extends ThreadPoolExecutor {
        private final BlockingQueue<Boolean> exits = new LinkedBlockingQueue<>();

        ObservedWorker() {
            super(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(4), runnable -> {
                Thread thread = new Thread(runnable, "lease-fence-worker");
                thread.setDaemon(true);
                return thread;
            });
        }

        @Override
        protected void afterExecute(Runnable task, Throwable failure) {
            super.afterExecute(task, failure);
            exits.add(Boolean.TRUE);
        }

        void awaitPhysicalExit() throws InterruptedException {
            assertNotNull(exits.poll(5, TimeUnit.SECONDS), "physical worker did not finish");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final InMemoryRunRepository runs = new InMemoryRunRepository();
        final InMemoryEventRepository events = new InMemoryEventRepository();
        final InMemoryChatMessageRepository messages = new InMemoryChatMessageRepository();
        final InMemoryAnswerProjectionRepository projections = new InMemoryAnswerProjectionRepository();
        final FaultyDurable durable = new FaultyDurable();
        final ObservedWorker worker = new ObservedWorker();
        final ControlledScheduler scheduler = new ControlledScheduler();
        final PokemonAgentRunService service;
        final CountDownLatch[] releases;

        Fixture(RunExecutor executor, CountDownLatch... releases) {
            this(executor, null, releases);
        }

        Fixture(RunExecutor executor, HitlManager hitlManager, CountDownLatch... releases) {
            RequestContext.clear();
            ToolExecutionContextHolder.clear();
            this.releases = releases;
            DurableRunProperties properties = new DurableRunProperties();
            properties.setDurableResume(true);
            properties.setLeaseTtlSeconds(30);
            properties.setHeartbeatSeconds(1);
            service = hitlManager == null
                    ? new PokemonAgentRunService(runs, events, new InMemoryConversationRepository(), messages,
                            executor, worker, scheduler, durable, properties)
                    : new PokemonAgentRunService(runs, events, new InMemoryConversationRepository(), messages,
                            executor, worker, scheduler, hitlManager, durable, properties);
            service.setAnswerProjectionRepository(projections);
        }

        @Override
        public void close() throws InterruptedException {
            for (CountDownLatch release : releases) release.countDown();
            service.shutdown();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS), "fixture worker must exit");
            RequestContext.clear();
            ToolExecutionContextHolder.clear();
        }
    }
}
