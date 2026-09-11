package com.vio.vioaiagent.run;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RunOutboxRepositoryTest {
    @Test
    void claimBindsOwnerAndRejectsForeignCompletion() {
        InMemoryRunOutboxRepository repository = new InMemoryRunOutboxRepository();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        repository.enqueue(RunOutboxMessage.pending("o-1", "run-1", 1, "RUN_STARTED", Map.of()));

        var claimed = repository.claimDue("worker-a", now, 10);
        assertEquals(1, claimed.size());
        assertEquals("worker-a", claimed.getFirst().claimOwner());
        assertFalse(repository.markPublished("o-1", "worker-b", now));
        assertTrue(repository.markPublished("o-1", "worker-a", now));
        assertEquals(RunOutboxMessage.OutboxStatus.PUBLISHED,
                repository.find("o-1").orElseThrow().status());
    }

    @Test
    void expiredClaimCanBeReclaimedByAnotherWorker() {
        InMemoryRunOutboxRepository repository = new InMemoryRunOutboxRepository();
        Instant first = Instant.parse("2026-01-01T00:00:00Z");
        repository.enqueue(RunOutboxMessage.pending("o-2", "run-1", 2, "RUN_STEP", Map.of()));
        repository.claimDue("worker-a", first, 10);

        var reclaimed = repository.claimDue("worker-b", first.plusSeconds(301), 10);
        assertEquals(1, reclaimed.size());
        assertEquals("worker-b", reclaimed.getFirst().claimOwner());
        assertTrue(repository.markFailed("o-2", "worker-b", "temporary", first.plusSeconds(400), false));
    }
}
