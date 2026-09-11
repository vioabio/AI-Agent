package com.vio.vioaiagent.run;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M3 并发回归：Run CAS 与事件序列不能被并发请求覆盖或打乱。 */
class RunConcurrencyTest {

    @Test
    void staleRunSnapshotIsRejectedByInMemoryCas() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        AgentRun current = run("run-cas");
        repository.save(current);
        assertEquals(0, current.version());

        assertTrue(current.transitionTo(AgentRunStatus.RUNNING));
        repository.save(current);
        assertEquals(1, current.version());

        AgentRun stale = AgentRun.restore(current.id(), current.sessionId(), current.conversationId(),
                current.userId(), current.prompt(), current.gameReleaseId(), current.battleFormat(),
                current.rulesetVersionId(), current.language(), current.traceId(), current.idempotencyKey(),
                current.createdAt(), current.deadlineAt(), AgentRunStatus.RUNNING, current.startedAt(),
                current.finishedAt(), 0);
        assertThrows(RunConflictException.class, () -> repository.save(stale));
        assertEquals(AgentRunStatus.RUNNING, repository.findById(current.id()).orElseThrow().status());
        assertEquals(1, repository.findById(current.id()).orElseThrow().version());
    }

    @Test
    void appendNextAllocatesContiguousIdsUnderConcurrency() throws Exception {
        InMemoryEventRepository repository = new InMemoryEventRepository();
        int count = 32;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<AgentEvent>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    return repository.appendNext(AgentEvent.of(1, AgentEventType.TOOL_FINISHED,
                            "session-cas", "run-events", "trace-cas", java.util.Map.of()));
                }));
            }
            gate.countDown();
            for (Future<AgentEvent> future : futures) future.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(count, repository.lastEventId("run-events"));
        List<AgentEvent> events = repository.findAfter("run-events", 0);
        assertEquals(count, events.size());
        for (int i = 0; i < events.size(); i++) assertEquals(i + 1L, events.get(i).eventId());
    }

    @Test
    void findActiveReturnsOnlyNonTerminalRuns() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        AgentRun active = run("run-active");
        AgentRun completed = run("run-completed");
        repository.save(active);
        repository.save(completed);

        assertTrue(completed.transitionTo(AgentRunStatus.RUNNING));
        assertTrue(completed.complete());
        repository.save(completed);

        List<AgentRun> activeRuns = repository.findActive();
        assertEquals(List.of(active), activeRuns);
    }

    @Test
    void rejectsNonTerminalEventsAfterTerminalEvent() {
        InMemoryEventRepository repository = new InMemoryEventRepository();
        AgentEvent started = repository.appendNext(AgentEvent.of(1, AgentEventType.TOOL_STARTED,
                "session-terminal", "run-terminal", "trace-terminal", java.util.Map.of()));
        repository.appendNext(AgentEvent.of(1, AgentEventType.RUN_COMPLETED,
                "session-terminal", "run-terminal", "trace-terminal", java.util.Map.of()));

        assertEquals(started, repository.append(started), "历史事件重放仍应幂等返回原事件");
        assertThrows(IllegalStateException.class, () -> repository.appendNext(
                AgentEvent.of(1, AgentEventType.TOOL_FINISHED,
                        "session-terminal", "run-terminal", "trace-terminal", java.util.Map.of())));
    }

    private AgentRun run(String id) {
        return AgentRun.create(id, "session-" + id, "conversation-" + id, "user-1", "prompt",
                "release-1", "singles", "rules-1", "zh-CN", "trace-" + id, null,
                Instant.now().plusSeconds(60));
    }
}
