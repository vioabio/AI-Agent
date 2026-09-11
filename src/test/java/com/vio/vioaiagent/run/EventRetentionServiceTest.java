package com.vio.vioaiagent.run;

import com.vio.vioaiagent.config.DurableRunProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventRetentionServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void disabledSweepDoesNotDeleteEvents() {
        InMemoryEventRepository repository = repositoryWithTerminalRun();
        DurableRunProperties properties = properties(false);
        EventRetentionService service = new EventRetentionService(repository, properties, CLOCK);

        EventRetentionService.RetentionSweepResult result = service.cleanupExpiredBatch();

        assertEquals("DISABLED", result.status());
        assertEquals(0, result.deleted());
        assertEquals(3, repository.findAfter("run-retention", 0).size());
    }

    @Test
    void enabledSweepRemovesOnlyExpiredNonTerminalEvents() {
        InMemoryEventRepository repository = repositoryWithTerminalRun();
        DurableRunProperties properties = properties(true);
        EventRetentionService service = new EventRetentionService(repository, properties, CLOCK);

        EventRetentionService.RetentionSweepResult result = service.cleanupExpiredBatch();

        assertEquals("COMPLETED", result.status());
        assertEquals(2, result.deleted());
        assertEquals(1, repository.findAfter("run-retention", 0).size());
        assertTrue(repository.hasTerminalEvent("run-retention"));
        assertEquals(3, repository.retainedFrom("run-retention"));
        // 游标 0 指向已清理区间，服务层应返回过期语义而不是从头补读。
        assertTrue(0 < repository.retainedFrom("run-retention") - 1);
    }

    @Test
    void activeRunIsNotPurgedEvenWhenOld() {
        InMemoryEventRepository repository = new InMemoryEventRepository();
        repository.append(new AgentEvent("1", 1, AgentEventType.RUN_CREATED, "s-active",
                "run-active", "trace-active", NOW.minusSeconds(3 * 86_400L), Map.of()));
        DurableRunProperties properties = properties(true);
        EventRetentionService service = new EventRetentionService(repository, properties, CLOCK);

        EventRetentionService.RetentionSweepResult result = service.cleanupExpiredBatch();

        assertEquals(0, result.deleted());
        assertFalse(repository.hasTerminalEvent("run-active"));
        assertEquals(1, repository.findAfter("run-active", 0).size());
    }

    @Test
    void manualCursorPurgeCannotRemoveTerminalEvent() {
        InMemoryEventRepository repository = repositoryWithTerminalRun();

        int deleted = repository.purgeBefore("run-retention", "run-events", Long.MAX_VALUE, 100);

        assertEquals(2, deleted);
        assertTrue(repository.hasTerminalEvent("run-retention"));
        assertEquals(1, repository.findAfter("run-retention", 0).size());
    }

    private static DurableRunProperties properties(boolean enabled) {
        DurableRunProperties properties = new DurableRunProperties();
        properties.setEventRetentionDays(1);
        properties.setEventRetentionCleanupEnabled(enabled);
        properties.setEventRetentionCleanupBatchSize(100);
        return properties;
    }

    private static InMemoryEventRepository repositoryWithTerminalRun() {
        InMemoryEventRepository repository = new InMemoryEventRepository();
        repository.append(new AgentEvent("1", 1, AgentEventType.RUN_CREATED, "s-retention",
                "run-retention", "trace-retention", NOW.minusSeconds(3 * 86_400L), Map.of()));
        repository.append(new AgentEvent("1", 2, AgentEventType.ANSWER_DELTA, "s-retention",
                "run-retention", "trace-retention", NOW.minusSeconds(2 * 86_400L), Map.of("text", "old")));
        repository.append(new AgentEvent("1", 3, AgentEventType.RUN_COMPLETED, "s-retention",
                "run-retention", "trace-retention", NOW.minusSeconds(2 * 86_400L), Map.of()));
        return repository;
    }
}
