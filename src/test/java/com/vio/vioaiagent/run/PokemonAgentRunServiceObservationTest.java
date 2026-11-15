package com.vio.vioaiagent.run;

import com.vio.vioaiagent.observability.AgentRunObservation;
import io.micrometer.observation.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PokemonAgentRunServiceObservationTest {
    private CreateRunCommand command(String prompt) {
        return new CreateRunCommand("owner", null, null, prompt, "sv-1", "singles", "rules-1", "zh-CN", null, null, Duration.ofSeconds(20));
    }
    private PokemonAgentRunService service(EventRepository events, RunExecutor executor, ObservationRegistry registry) {
        var service = new PokemonAgentRunService(new InMemoryRunRepository(), events,
                new InMemoryConversationRepository(), new InMemoryChatMessageRepository(), executor,
                Executors.newSingleThreadExecutor(), Executors.newSingleThreadScheduledExecutor());
        service.setObservationRegistry(registry);
        return service;
    }
    private PokemonAgentRunService limitedService(EventRepository events, RunExecutor executor, ObservationRegistry registry, long queueWaitMs) {
        var limits = new com.vio.vioaiagent.config.RunAdmissionProperties();
        limits.setThreadCount(1); limits.setQueueCapacity(1); limits.setQueueWaitMs(queueWaitMs);
        var service = new PokemonAgentRunService(new InMemoryRunRepository(), events,
                new InMemoryConversationRepository(), new InMemoryChatMessageRepository(), executor,
                PokemonAgentRunService.newWorker(limits), Executors.newSingleThreadScheduledExecutor(), limits);
        service.setObservationRegistry(registry);
        return service;
    }
    private static final class Capture implements ObservationHandler<Observation.Context> {
        final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();
        final CountDownLatch roots;
        Capture(int attempts) { roots = new CountDownLatch(attempts); }
        public boolean supportsContext(Observation.Context context) { return context.getName().startsWith("agent.run."); }
        public void onStop(Observation.Context context) {
            stopped.add(context);
            if (context.getName().equals("agent.run.execute")) roots.countDown();
        }
        void await() throws InterruptedException { assertTrue(roots.await(5, TimeUnit.SECONDS)); }
        Observation.Context root() { return stopped.stream().filter(c -> c.getName().equals("agent.run.execute")).findFirst().orElseThrow(); }
    }
    @Test void normalEntryIncludesAuthorizationGenerationAndCommitUnderOneRoot() throws Exception {
        var registry = ObservationRegistry.create(); var capture = new Capture(1);
        registry.observationConfig().observationHandler(capture);
        var events = new InMemoryEventRepository();
        var service = service(events, context -> new AgentRunObservation(registry).execute(context.run().id(), () -> {
            assertEquals("agent.run.execute", registry.getCurrentObservation().getContext().getName());
            return "回答";
        }), registry);
        try {
            var run = service.createRun(command("你好"));
            assertEquals(AgentEventType.RUN_COMPLETED, RunTestAwait.awaitTerminalEvent(events, run.runId()).type());
            capture.await();
            assertEquals(5, capture.stopped.size());
            var root = capture.root();
            assertEquals("COMPLETED", root.getLowCardinalityKeyValue("agent.run.status").getValue());
            assertEquals(run.runId(), root.getHighCardinalityKeyValue("agent.run.id").getValue());
            for (var phase : capture.stopped.stream().filter(c -> Set.of("agent.run.authorize", "agent.run.commit").contains(c.getName())).toList())
                assertSame(root, phase.getParentObservation().getContextView());
            var dispatch = capture.stopped.stream().filter(c -> c.getName().equals("agent.run.dispatch")).findFirst().orElseThrow();
            assertEquals(root.getHighCardinalityKeyValue("agent.run.attempt.id"), dispatch.getHighCardinalityKeyValue("agent.run.attempt.id"));
            assertEquals("agent.run.admit", dispatch.getParentObservation().getContextView().getName());
            assertNull(root.getError()); assertNull(registry.getCurrentObservation());
        } finally { service.shutdown(); }
    }
    @Test void commitFailureIsSanitizedAndDoesNotReplayGeneration() throws Exception {
        var registry = ObservationRegistry.create(); var capture = new Capture(1);
        registry.observationConfig().observationHandler(capture);
        var events = new InMemoryEventRepository(); var generated = new AtomicInteger();
        var service = service(events, context -> { generated.incrementAndGet(); return "回答"; }, registry);
        var projections = mock(AnswerProjectionRepository.class);
        when(projections.save(any())).thenThrow(new IllegalStateException("PRIVATE_STORAGE_DETAIL"));
        service.setAnswerProjectionRepository(projections);
        try {
            var run = service.createRun(command("你好"));
            assertEquals(AgentEventType.RUN_FAILED, RunTestAwait.awaitTerminalEvent(events, run.runId()).type());
            capture.await(); assertEquals(1, generated.get());
            assertEquals("RUN_FAILED", capture.root().getError().getMessage());
            var commit = capture.stopped.stream().filter(c -> c.getName().equals("agent.run.commit")).findFirst().orElseThrow();
            assertEquals("RUN_COMMIT_FAILED", commit.getError().getMessage());
            assertFalse(capture.stopped.toString().contains("PRIVATE_STORAGE_DETAIL"));
            verify(projections, times(1)).save(any());
        } finally { service.shutdown(); }
    }
    @Test void cancellationStopsTheAttemptWithoutPublishingAnAnswer() throws Exception {
        var registry = ObservationRegistry.create(); var capture = new Capture(1);
        registry.observationConfig().observationHandler(capture);
        var events = new InMemoryEventRepository(); var entered = new CountDownLatch(1);
        var service = service(events, context -> { entered.countDown(); new CountDownLatch(1).await(); return "未完成"; }, registry);
        try {
            var run = service.createRun(command("等待")); assertTrue(entered.await(3, TimeUnit.SECONDS));
            service.cancel(run.runId(), "owner");
            assertEquals(AgentEventType.RUN_CANCELLED, RunTestAwait.awaitTerminalEvent(events, run.runId()).type());
            capture.await();
            assertEquals("CANCELLED", capture.root().getLowCardinalityKeyValue("agent.run.status").getValue());
            assertTrue(capture.stopped.stream().noneMatch(c -> c.getName().equals("agent.run.commit")));
            assertTrue(events.findAfter(run.runId(), 0).stream().noneMatch(e -> e.type() == AgentEventType.ANSWER_DELTA));
        } finally { service.shutdown(); }
    }
    @Test void brokenObservationHandlerCannotReplayOrBlockTheBusinessAction() {
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            public boolean supportsContext(Observation.Context context) { return true; }
            public void onStart(Observation.Context context) { throw new IllegalStateException("EXPORTER_UNAVAILABLE"); }
        });
        var calls = new AtomicInteger(); var events = new InMemoryEventRepository();
        var service = service(events, context -> { calls.incrementAndGet(); return "回答"; }, registry);
        try {
            var run = service.createRun(command("你好"));
            assertEquals(AgentEventType.RUN_COMPLETED, RunTestAwait.awaitTerminalEvent(events, run.runId()).type());
            assertEquals(1, calls.get());
        } finally { service.shutdown(); }
    }
    @Test void queuedWorkerRecordsWaitAndRestoresTheCurrentRun() throws Exception {
        var registry = ObservationRegistry.create(); var capture = new Capture(2);
        registry.observationConfig().observationHandler(capture);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var events = new InMemoryEventRepository();
        var service = service(events, context -> {
            assertEquals(context.run().id(), registry.getCurrentObservation().getContext()
                    .getHighCardinalityKeyValue("agent.run.id").getValue());
            if (context.run().prompt().equals("hold")) { entered.countDown(); release.await(); }
            return "回答";
        }, registry);
        try {
            service.createRun(command("hold")); assertTrue(entered.await(3, TimeUnit.SECONDS));
            var queued = service.createRun(command("queued"));
            Thread.sleep(35); release.countDown();
            RunTestAwait.awaitTerminalEvent(events, queued.runId()); capture.await();
            var root = capture.stopped.stream().filter(context -> context.getName().equals("agent.run.execute")
                    && queued.runId().equals(context.getHighCardinalityKeyValue("agent.run.id").getValue())).findFirst().orElseThrow();
            assertTrue(Long.parseLong(root.getHighCardinalityKeyValue("agent.run.dispatch_wait_ms").getValue()) >= 25);
            assertEquals(2, capture.stopped.stream().filter(context -> context.getName().equals("agent.run.execute")).count());
        } finally { release.countDown(); service.shutdown(); }
    }
    @Test void queuedCancellationClosesDispatchWithoutStartingAnotherWorker() throws Exception {
        var registry = ObservationRegistry.create(); var capture = new Capture(1);
        registry.observationConfig().observationHandler(capture);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        var events = new InMemoryEventRepository();
        var service = service(events, context -> { calls.incrementAndGet(); entered.countDown(); release.await(); return "回答"; }, registry);
        try {
            service.createRun(command("hold")); assertTrue(entered.await(3, TimeUnit.SECONDS));
            var queued = service.createRun(command("queued")); service.cancel(queued.runId(), "owner");
            RunTestAwait.awaitTerminalEvent(events, queued.runId());
            var dispatch = capture.stopped.stream().filter(c -> c.getName().equals("agent.run.dispatch")
                    && queued.runId().equals(c.getHighCardinalityKeyValue("agent.run.id").getValue())).findFirst().orElseThrow();
            assertEquals("CANCELLED", dispatch.getLowCardinalityKeyValue("agent.run.dispatch.outcome").getValue());
            assertEquals(1, calls.get());
            assertTrue(capture.stopped.stream().noneMatch(c -> c.getName().equals("agent.run.execute")
                    && queued.runId().equals(c.getHighCardinalityKeyValue("agent.run.id").getValue())));
        } finally { release.countDown(); service.shutdown(); }
    }
    @Test void approvalResumeKeepsTheRunAndUsesDistinctAttemptIdentities() throws Exception {
        var registry = ObservationRegistry.create(); var capture = new Capture(2);
        registry.observationConfig().observationHandler(capture);
        var events = new InMemoryEventRepository(); var calls = new AtomicInteger();
        var service = service(events, context -> {
            if (calls.incrementAndGet() == 1) {
                com.vio.vioaiagent.tools.governance.ToolExecutionContextHolder.current().eventSink().accept(
                        new com.vio.vioaiagent.tools.governance.ToolGovernanceEvent("approval.required", context.run().id(),
                                "test_preview", "test-approval", java.time.Instant.now(), Map.of("approvalId", "approval-test",
                                "expiresAt", java.time.Instant.now().plusSeconds(120).toString(), "riskLevel", "MEDIUM")));
                return "等待确认";
            }
            return "确认后完成";
        }, registry);
        try {
            var run = service.createRun(command("审批恢复"));
            RunTestAwait.awaitEvent(events, run.runId(), AgentEventType.APPROVAL_REQUIRED);
            service.resolveApproval(run.runId(), "owner", true, "approved");
            assertEquals(AgentEventType.RUN_COMPLETED, RunTestAwait.awaitTerminalEvent(events, run.runId()).type());
            capture.await();
            var roots = capture.stopped.stream().filter(c -> c.getName().equals("agent.run.execute")).toList();
            assertEquals(2, roots.size()); assertEquals(2, calls.get());
            assertEquals(2, roots.stream().map(c -> c.getHighCardinalityKeyValue("agent.run.attempt.id").getValue()).distinct().count());
            assertTrue(roots.stream().allMatch(c -> run.runId().equals(c.getHighCardinalityKeyValue("agent.run.id").getValue())));
            assertEquals(2, capture.stopped.stream().filter(c -> c.getName().equals("agent.run.dispatch")).count());
        } finally { service.shutdown(); }
    }
    @Test void queueExpiryEndsDispatchAndNeverInvokesTheQueuedAction() throws Exception {
        var registry = ObservationRegistry.create(); var capture = new Capture(1);
        registry.observationConfig().observationHandler(capture);
        var stopped = new CountDownLatch(1);
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            public boolean supportsContext(Observation.Context context) { return "agent.run.dispatch".equals(context.getName()); }
            public void onStop(Observation.Context context) {
                if ("FAILED".equals(context.getLowCardinalityKeyValue("agent.run.dispatch.outcome").getValue())) stopped.countDown();
            }
        });
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        var events = new InMemoryEventRepository();
        var service = limitedService(events, context -> { calls.incrementAndGet(); entered.countDown(); release.await(); return "回答"; }, registry, 50);
        try {
            service.createRun(command("hold")); assertTrue(entered.await(3, TimeUnit.SECONDS));
            var queued = service.createRun(command("queued"));
            assertEquals(AgentEventType.RUN_FAILED, RunTestAwait.awaitTerminalEvent(events, queued.runId()).type());
            assertTrue(stopped.await(3, TimeUnit.SECONDS)); assertEquals(1, calls.get());
        } finally { release.countDown(); service.shutdown(); }
    }
    @Test void admissionRejectionHasAFixedErrorWithoutCreatingAnotherTask() throws Exception {
        var registry = ObservationRegistry.create(); var capture = new Capture(1);
        registry.observationConfig().observationHandler(capture);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var service = limitedService(new InMemoryEventRepository(), context -> { entered.countDown(); release.await(); return "回答"; }, registry, 30000);
        try {
            service.createRun(command("hold")); assertTrue(entered.await(3, TimeUnit.SECONDS));
            service.createRun(command("queued"));
            assertThrows(RunAdmissionException.class, () -> service.createRun(command("private request")));
            var rejected = capture.stopped.stream().filter(c -> c.getName().equals("agent.run.admit") && c.getError() != null).findFirst().orElseThrow();
            assertEquals("RUN_ADMISSION_FAILED", rejected.getError().getMessage());
            assertNull(rejected.getHighCardinalityKeyValue("agent.run.id"));
            assertEquals(2, service.runtimeSnapshot().tasks());
        } finally { release.countDown(); service.shutdown(); }
    }
}
