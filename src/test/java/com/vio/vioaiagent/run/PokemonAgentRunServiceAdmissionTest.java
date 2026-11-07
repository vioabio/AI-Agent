package com.vio.vioaiagent.run;

import com.vio.vioaiagent.config.RunAdmissionProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Admission must follow physical callback lifetime, including cancellation races. */
class PokemonAgentRunServiceAdmissionTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fullCapacityRejectsBeforePersistenceAndAllowsIdempotentReplay(boolean durableIdempotency) throws Exception {
        Gate gate = new Gate();
        try (Fixture f = new Fixture(gate::execute, 30_000)) {
            InMemoryIdempotencyStore idempotency = spy(new InMemoryIdempotencyStore());
            if (durableIdempotency) f.service.setIdempotencyStore(idempotency);
            CreateRunCommand first = command("first", Duration.ofSeconds(30));
            RunView running = f.service.createRun(first);
            assertTrue(gate.started.await(3, TimeUnit.SECONDS));
            RunView queued = f.service.createRun(command("queued", Duration.ofSeconds(30)));
            assertEquals(2, f.service.runtimeSnapshot().reserved());
            assertEquals(1, f.worker.getQueue().size());
            clearInvocations(f.runs, f.events, f.conversations, f.messages, idempotency);

            RunAdmissionException error = assertThrows(RunAdmissionException.class,
                    () -> f.service.createRun(command("rejected", Duration.ofSeconds(30))));
            assertEquals("RUN_CAPACITY_EXCEEDED", error.code());
            assertFalse(error.unavailable());
            verify(f.runs, never()).save(any());
            verify(f.conversations, never()).save(any());
            verify(f.messages, never()).save(any());
            verify(f.events, never()).appendNext(any());
            verify(idempotency, never()).claim(anyString(), anyString(), anyString(), any(), any());
            assertEquals(running.runId(), f.service.createRun(first).runId());
            assertEquals(2, f.service.runtimeSnapshot().reserved());

            f.service.cancel(queued.runId(), "owner");
            assertEquals(0, f.worker.getQueue().size());
            assertEquals(1, f.service.runtimeSnapshot().reserved());
            assertNotNull(f.service.createRun(command("rejected", Duration.ofSeconds(30))),
                    "429 must leave the same retry key usable");
        } finally { gate.release.countDown(); }
    }

    @Test
    void cancellingRunningInterruptIgnoringCallbackRetainsReservationUntilPhysicalExit() throws Exception {
        Gate gate = new Gate();
        try (Fixture f = new Fixture(gate::execute, 30_000)) {
            RunView running = f.service.createRun(command("running", Duration.ofSeconds(30)));
            assertTrue(gate.started.await(3, TimeUnit.SECONDS));
            RunView queued = f.service.createRun(command("queued", Duration.ofSeconds(30)));
            f.service.cancel(running.runId(), "owner");
            assertTrue(gate.interrupted.await(3, TimeUnit.SECONDS));
            assertEquals(AgentRunStatus.CANCELLED, f.runs.findById(running.runId()).orElseThrow().status());
            assertEquals(2, f.service.runtimeSnapshot().reserved());
            assertEquals(2, f.service.runtimeSnapshot().tasks());
            assertThrows(RunAdmissionException.class,
                    () -> f.service.createRun(command("extra", Duration.ofSeconds(30))));
            f.service.cancel(queued.runId(), "owner");
            assertEquals(1, f.service.runtimeSnapshot().reserved());
            gate.release.countDown();
            await(() -> f.service.runtimeSnapshot().reserved() == 0);
            assertIdle(f);
            assertEquals(1, gate.calls.get());
        } finally { gate.release.countDown(); }
    }

    @Test
    void queuedExpiryNeverCallsExecutorAndReleasesQueueAndTimers() throws Exception {
        Gate gate = new Gate();
        try (Fixture f = new Fixture(gate::execute, 250)) {
            f.service.createRun(command("running", Duration.ofSeconds(30)));
            assertTrue(gate.started.await(3, TimeUnit.SECONDS));
            RunView queued = f.service.createRun(command("expires", Duration.ofSeconds(30)));
            AgentEvent terminal = RunTestAwait.awaitTerminalEvent(f.events, queued.runId());
            assertEquals(AgentEventType.RUN_FAILED, terminal.type());
            assertEquals("RUN_QUEUE_TIMEOUT", terminal.payload().get("errorType"));
            await(() -> f.service.runtimeSnapshot().reserved() == 1);
            assertEquals(0, f.worker.getQueue().size());
            assertEquals(0, f.service.runtimeSnapshot().queueTimers());
            assertEquals(1, f.service.runtimeSnapshot().deadlineTimers());
            assertEquals(1, gate.calls.get());
        } finally { gate.release.countDown(); }
    }

    @Test
    void queueTtlIsEnforcedAtDispatchWhenSchedulerIsBlocked() throws Exception {
        Gate gate = new Gate();
        CountDownLatch timerRelease = new CountDownLatch(1);
        try (Fixture f = new Fixture(gate::execute, 250)) {
            blockScheduler(f.scheduler, timerRelease);
            f.service.createRun(command("running", Duration.ofSeconds(30)));
            assertTrue(gate.started.await(3, TimeUnit.SECONDS));
            RunView queued = f.service.createRun(command("expires", Duration.ofSeconds(30)));
            long queuedAt = System.nanoTime();
            await(() -> System.nanoTime() - queuedAt >= TimeUnit.MILLISECONDS.toNanos(300));
            assertEquals(AgentRunStatus.CREATED, f.runs.findById(queued.runId()).orElseThrow().status());
            gate.release.countDown();
            AgentEvent terminal = RunTestAwait.awaitTerminalEvent(f.events, queued.runId());
            assertEquals("RUN_QUEUE_TIMEOUT", terminal.payload().get("errorType"));
            assertEquals(1, gate.calls.get());
            await(() -> f.service.runtimeSnapshot().reserved() == 0);
            assertIdle(f);
        } finally { timerRelease.countDown(); gate.release.countDown(); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void queuedTotalDeadlinePreventsModelDispatchEvenWithDelayedTimer(boolean delayScheduler) throws Exception {
        Gate gate = new Gate();
        CountDownLatch timerRelease = new CountDownLatch(1);
        try (Fixture f = new Fixture(gate::execute, 30_000)) {
            if (delayScheduler) blockScheduler(f.scheduler, timerRelease);
            f.service.createRun(command("running", Duration.ofSeconds(30)));
            assertTrue(gate.started.await(3, TimeUnit.SECONDS));
            RunView queued = f.service.createRun(command("deadline", Duration.ofMillis(150)));
            if (delayScheduler) {
                await(() -> !java.time.Instant.now().isBefore(
                        f.runs.findById(queued.runId()).orElseThrow().deadlineAt()));
                gate.release.countDown();
            }
            assertEquals(AgentEventType.RUN_TIMED_OUT,
                    RunTestAwait.awaitTerminalEvent(f.events, queued.runId()).type());
            assertEquals(1, gate.calls.get());
            await(() -> f.worker.getQueue().isEmpty());
        } finally { timerRelease.countDown(); gate.release.countDown(); }
    }

    @Test
    void delayedDeadlineTimerCannotCommitAnOverdueRunningAnswer() throws Exception {
        Gate gate = new Gate();
        CountDownLatch timerRelease = new CountDownLatch(1);
        try (Fixture f = new Fixture(gate::execute, 30_000)) {
            blockScheduler(f.scheduler, timerRelease);
            RunView running = f.service.createRun(command("running", Duration.ofMillis(200)));
            assertTrue(gate.started.await(3, TimeUnit.SECONDS));
            await(() -> !java.time.Instant.now().isBefore(
                    f.runs.findById(running.runId()).orElseThrow().deadlineAt()));
            gate.release.countDown();
            assertEquals(AgentEventType.RUN_TIMED_OUT,
                    RunTestAwait.awaitTerminalEvent(f.events, running.runId()).type());
            await(() -> f.service.runtimeSnapshot().reserved() == 0);
            assertEquals(1, gate.calls.get());
            assertTrue(f.messages.findByConversationForUser(running.conversationId(), "owner").stream()
                    .noneMatch(message -> "assistant".equals(message.role())));
            assertIdle(f);
        } finally { timerRelease.countDown(); gate.release.countDown(); }
    }

    @Test
    void shutdownCancelsQueuedWorkAndRefusesNewWorkWhilePhysicalCallbackFinishes() throws Exception {
        Gate gate = new Gate();
        try (Fixture f = new Fixture(gate::execute, 30_000)) {
            RunView running = f.service.createRun(command("running", Duration.ofSeconds(30)));
            assertTrue(gate.started.await(3, TimeUnit.SECONDS));
            RunView queued = f.service.createRun(command("queued", Duration.ofSeconds(30)));
            f.service.shutdown();
            assertTrue(gate.interrupted.await(3, TimeUnit.SECONDS));
            assertEquals(AgentRunStatus.CANCELLED, f.runs.findById(running.runId()).orElseThrow().status());
            assertEquals(AgentRunStatus.CANCELLED, f.runs.findById(queued.runId()).orElseThrow().status());
            assertEquals(1, f.service.runtimeSnapshot().reserved());
            assertEquals(0, f.service.runtimeSnapshot().queueTimers());
            assertEquals(0, f.service.runtimeSnapshot().deadlineTimers());
            assertFalse(f.service.runtimeSnapshot().accepting());
            assertEquals("RUN_EXECUTION_UNAVAILABLE", assertThrows(RunAdmissionException.class,
                    () -> f.service.createRun(command("extra", Duration.ofSeconds(30)))).code());
            gate.release.countDown();
            await(() -> f.service.runtimeSnapshot().reserved() == 0);
            assertIdle(f);
        } finally { gate.release.countDown(); }
    }

    private static CreateRunCommand command(String key, Duration timeout) {
        return new CreateRunCommand("owner", "session", null, key, "sv-1", "singles",
                "rules-1", "zh-CN", "trace", key, timeout);
    }

    private static void blockScheduler(ScheduledThreadPoolExecutor scheduler, CountDownLatch release) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        scheduler.execute(() -> {
            entered.countDown();
            uninterruptibly(release, null);
        });
        assertTrue(entered.await(3, TimeUnit.SECONDS));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "timed out waiting for lifecycle condition");
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
        assertTrue(f.scheduler.getQueue().isEmpty());
    }

    private static void uninterruptibly(CountDownLatch release, CountDownLatch interrupted) {
        boolean restore = false;
        while (release.getCount() != 0) {
            try { release.await(); }
            catch (InterruptedException ex) { restore = true; if (interrupted != null) interrupted.countDown(); }
        }
        if (restore) Thread.currentThread().interrupt();
    }

    private static final class Gate {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        String execute(AgentRunContext context) {
            calls.incrementAndGet();
            started.countDown();
            uninterruptibly(release, interrupted);
            return "done";
        }
    }

    private static final class Fixture implements AutoCloseable {
        final InMemoryRunRepository runs = spy(new InMemoryRunRepository());
        final InMemoryEventRepository events = spy(new InMemoryEventRepository());
        final InMemoryConversationRepository conversations = spy(new InMemoryConversationRepository());
        final InMemoryChatMessageRepository messages = spy(new InMemoryChatMessageRepository());
        final ThreadPoolExecutor worker;
        final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        final PokemonAgentRunService service;
        Fixture(RunExecutor executor, long queueWaitMs) {
            RunAdmissionProperties limits = new RunAdmissionProperties();
            limits.setThreadCount(1);
            limits.setQueueCapacity(1);
            limits.setMaxPerPrincipal(4);
            limits.setMaxPerTenant(4);
            limits.setQueueWaitMs(queueWaitMs);
            worker = PokemonAgentRunService.newWorker(limits);
            service = new PokemonAgentRunService(runs, events, conversations, messages,
                    executor, worker, scheduler, limits);
        }
        @Override public void close() { service.shutdown(); }
    }
}
