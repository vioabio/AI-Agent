package com.vio.vioaiagent.run;

import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.config.DurableRunProperties;
import com.vio.vioaiagent.config.RunAdmissionProperties;
import com.vio.vioaiagent.observability.AgentMetrics;
import com.vio.vioaiagent.security.HitlManager;
import com.vio.vioaiagent.tools.governance.ToolExecutionContextHolder;
import com.vio.vioaiagent.tools.governance.ToolGovernanceEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Failed storage and dispatch boundaries must not strand local admission reservations. */
class PokemonAgentRunServiceAdmissionFailureTest {

    @BeforeEach
    @AfterEach
    void clearRequestContext() {
        RequestContext.clear();
        ToolExecutionContextHolder.clear();
    }

    @Test
    void checkpointFailureBeforeDispatchFailsRunAndReleasesCapacity() {
        try (Fixture fixture = new Fixture()) {
            InMemoryDurableRunStateRepository checkpoints = spy(new InMemoryDurableRunStateRepository());
            doThrow(new IllegalStateException("checkpoint unavailable"))
                    .when(checkpoints).save(any(AgentCheckpoint.class));
            fixture.startWithCheckpoints(checkpoints);

            assertThrows(IllegalStateException.class, () -> fixture.service.createRun(command()));

            assertFailedBeforeExecution(fixture);
        }
    }

    @Test
    void createdEventFailureBeforeDispatchFailsRunAndReleasesCapacity() {
        try (Fixture fixture = new Fixture()) {
            doThrow(new IllegalStateException("created event unavailable")).when(fixture.events)
                    .appendNext(argThat(event -> event != null && event.type() == AgentEventType.RUN_CREATED));
            fixture.start();

            assertThrows(IllegalStateException.class, () -> fixture.service.createRun(command()));

            assertFailedBeforeExecution(fixture);
        }
    }

    @Test
    void messageFailureBeforeDispatchFailsRunAndReleasesCapacity() {
        try (Fixture fixture = new Fixture()) {
            doThrow(new IllegalStateException("message unavailable"))
                    .when(fixture.messages).save(any(ChatMessage.class));
            fixture.start();

            assertThrows(IllegalStateException.class, () -> fixture.service.createRun(command()));

            assertFailedBeforeExecution(fixture);
            AgentRun failed = fixture.persisted();
            assertTrue(fixture.messages.findByConversationForUser(failed.conversationId(), failed.userId()).isEmpty());
        }
    }

    @Test
    void workerRejectionCancelsRegisteredTaskAndReleasesCapacity() {
        try (Fixture fixture = new Fixture()) {
            ExecutorService worker = mock(ExecutorService.class);
            doThrow(new RejectedExecutionException("worker rejected")).when(worker).execute(any(Runnable.class));
            when(worker.shutdownNow()).thenReturn(List.of());
            fixture.start(worker, new ScheduledThreadPoolExecutor(1));

            RunAdmissionException rejection = assertThrows(RunAdmissionException.class,
                    () -> fixture.service.createRun(command()));

            assertEquals("RUN_EXECUTION_UNAVAILABLE", rejection.code());
            assertTrue(rejection.unavailable());
            assertFailedBeforeExecution(fixture);
        }
    }

    @Test
    void deadlineSchedulingRejectionCancelsTaskAndReleasesCapacity() {
        try (Fixture fixture = new Fixture()) {
            fixture.start(PokemonAgentRunService.newWorker(fixture.properties), new RejectingScheduler(1));

            assertThrows(RunAdmissionException.class, () -> fixture.service.createRun(command()));

            assertFailedBeforeExecution(fixture);
        }
    }

    @Test
    void queueSchedulingRejectionAlsoRemovesPreviouslyArmedDeadline() {
        try (Fixture fixture = new Fixture()) {
            RejectingScheduler scheduler = new RejectingScheduler(2);
            fixture.start(PokemonAgentRunService.newWorker(fixture.properties), scheduler);

            assertThrows(RunAdmissionException.class, () -> fixture.service.createRun(command()));

            assertFailedBeforeExecution(fixture);
            assertTrue(scheduler.getQueue().isEmpty(), "Cancelled deadline must leave the scheduler queue");
        }
    }

    @Test
    void leaseAcquisitionExceptionLeavesRunCreatedAndReleasesLocalStateWithoutClaimingOwnership() {
        try (Fixture fixture = new Fixture()) {
            InMemoryDurableRunStateRepository checkpoints = spy(new InMemoryDurableRunStateRepository());
            doThrow(new IllegalStateException("lease unavailable")).when(checkpoints)
                    .acquireLease(anyString(), anyString(), anyString(), any(Duration.class));
            fixture.startWithCheckpoints(checkpoints);

            RunView created = fixture.service.createRun(command());

            assertReleased(fixture.service);
            assertEquals(0, fixture.executions.get());
            assertEquals(AgentRunStatus.CREATED, fixture.persisted().status());
            assertFalse(fixture.events.hasTerminalEvent(created.runId()),
                    "Unconfirmed ownership must not authorize a local worker to write FAILED or another terminal state");
            verify(checkpoints).releaseLease(eq(created.runId()), anyString(), anyString());
        }
    }

    @Test
    void deniedLeaseReleasesLocalCapacityWithoutExecutingOrInventingTerminalState() {
        try (Fixture fixture = new Fixture()) {
            InMemoryDurableRunStateRepository checkpoints = spy(new InMemoryDurableRunStateRepository());
            doReturn(Optional.empty()).when(checkpoints)
                    .acquireLease(anyString(), anyString(), anyString(), any(Duration.class));
            fixture.startWithCheckpoints(checkpoints);

            RunView created = fixture.service.createRun(command());

            assertReleased(fixture.service);
            assertEquals(0, fixture.executions.get());
            assertEquals(AgentRunStatus.CREATED, fixture.persisted().status());
            assertFalse(fixture.events.hasTerminalEvent(created.runId()),
                    "A local worker denied ownership must not overwrite another owner's lifecycle");
            verify(checkpoints).releaseLease(eq(created.runId()), anyString(), anyString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancel", "deny"})
    void waitingApprovalReleasesCapacityEvenWhenTerminalEventCannotBeWritten(String action) {
        try (Fixture fixture = new Fixture()) {
            fixture.executor = context -> {
                fixture.executions.incrementAndGet();
                ToolExecutionContextHolder.current().eventSink().accept(new ToolGovernanceEvent(
                        "approval.required", context.run().id(), "write_file", "failure-approval",
                        Instant.now(), Map.of("riskLevel", "MEDIUM")));
                return "must remain unpublished";
            };
            fixture.start();
            RunView created = fixture.service.createRun(command());
            RunTestAwait.awaitEvent(fixture.events, created.runId(), AgentEventType.APPROVAL_REQUIRED);
            await(() -> fixture.service.runtimeSnapshot().tasks() == 0,
                    "Approval worker must physically exit before injecting terminal event failure");
            assertEquals(1, fixture.service.runtimeSnapshot().reserved());
            doThrow(new IllegalStateException("terminal event unavailable")).when(fixture.events)
                    .appendNext(argThat(event -> event != null && event.type().isTerminal()));

            if ("cancel".equals(action)) {
                assertThrows(IllegalStateException.class,
                        () -> fixture.service.cancel(created.runId(), "failure-user"));
                assertEquals(AgentRunStatus.CANCELLED, fixture.persisted().status());
            } else {
                assertThrows(IllegalStateException.class,
                        () -> fixture.service.resolveApproval(created.runId(), "failure-user", false, "denied"));
                assertEquals(AgentRunStatus.FAILED, fixture.persisted().status());
            }

            assertReleased(fixture.service);
            assertEquals(1, fixture.executions.get());
            assertFalse(fixture.events.hasTerminalEvent(created.runId()));
        }
    }

    private static void assertFailedBeforeExecution(Fixture fixture) {
        assertReleased(fixture.service);
        AgentRun failed = fixture.persisted();
        assertEquals(AgentRunStatus.FAILED, failed.status());
        assertEquals(0, fixture.executions.get());
        assertEquals(1, fixture.events.findAfter(failed.id(), 0).stream()
                .filter(event -> event.type().isTerminal()).count());
    }

    private static void assertReleased(PokemonAgentRunService service) {
        await(() -> {
            var snapshot = service.runtimeSnapshot();
            return snapshot.reserved() == 0 && snapshot.tasks() == 0
                    && snapshot.deadlineTimers() == 0 && snapshot.queueTimers() == 0
                    && snapshot.principalScopes() == 0 && snapshot.tenantScopes() == 0;
        }, "All local task, timer and admission state must be released: " + service.runtimeSnapshot());
    }

    private static void await(BooleanSupplier condition, String message) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
            if (Thread.currentThread().isInterrupted()) fail("Interrupted while waiting: " + message);
        }
        fail(message);
    }

    private static CreateRunCommand command() {
        return new CreateRunCommand("failure-user", "failure-session", null, "failure fixture",
                "sv-1", "singles", "rules-1", "zh-CN", "failure-trace", "failure-key",
                Duration.ofSeconds(20));
    }

    private static <T> ObjectProvider<T> provider(Class<T> type, T instance) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (instance != null) factory.addBean("fixture", instance);
        return factory.getBeanProvider(type);
    }

    private static final class Fixture implements AutoCloseable {
        private final InMemoryRunRepository runs = new InMemoryRunRepository();
        private final InMemoryEventRepository events = spy(new InMemoryEventRepository());
        private final InMemoryConversationRepository conversations = new InMemoryConversationRepository();
        private final InMemoryChatMessageRepository messages = spy(new InMemoryChatMessageRepository());
        private final RunAdmissionProperties properties = new RunAdmissionProperties();
        private final AtomicInteger executions = new AtomicInteger();
        private RunExecutor executor = context -> { executions.incrementAndGet(); return "result"; };
        private PokemonAgentRunService service;

        private Fixture() {
            properties.setThreadCount(1);
            properties.setQueueCapacity(1);
            properties.setMaxPerPrincipal(2);
            properties.setMaxPerTenant(2);
        }

        private void start() {
            start(PokemonAgentRunService.newWorker(properties), new ScheduledThreadPoolExecutor(1));
        }

        private void start(ExecutorService worker, ScheduledExecutorService scheduler) {
            service = new PokemonAgentRunService(runs, events, conversations, messages,
                    executor, worker, scheduler, properties);
        }

        private void startWithCheckpoints(CheckpointRepository checkpoints) {
            DurableRunProperties durable = new DurableRunProperties();
            durable.setDurableResume(true);
            service = new PokemonAgentRunService(runs, events, conversations, messages,
                    provider(RunExecutor.class, executor), provider(AgentMetrics.class, null),
                    provider(HitlManager.class, null), provider(RunOutboxRepository.class, null),
                    provider(CheckpointRepository.class, checkpoints), provider(DurableRunProperties.class, durable),
                    provider(RunAdmissionProperties.class, properties));
        }

        private AgentRun persisted() {
            return runs.findByIdempotencyKey("failure-user", "failure-key").orElseThrow();
        }

        @Override public void close() {
            if (service != null) service.shutdown();
        }
    }

    private static final class RejectingScheduler extends ScheduledThreadPoolExecutor {
        private final AtomicInteger schedules = new AtomicInteger();
        private final int rejectedCall;

        private RejectingScheduler(int rejectedCall) { super(1); this.rejectedCall = rejectedCall; }

        @Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            if (schedules.incrementAndGet() == rejectedCall) throw new RejectedExecutionException("timer rejected");
            return super.schedule(command, delay, unit);
        }
    }
}
