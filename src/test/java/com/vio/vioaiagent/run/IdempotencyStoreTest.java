package com.vio.vioaiagent.run;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** N2 通用 HTTP 幂等记录：pending、重放、冲突和 TTL 清理。 */
class IdempotencyStoreTest {
    @Test
    void pendingThenCompleteReplaysStableBody() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        Instant now = Instant.parse("2026-09-03T00:00:00Z");
        IdempotencyStore.Claim first = store.claim("run:create:u1", "k1", "h1",
                Duration.ofMinutes(5), now);
        assertTrue(first.accepted());
        IdempotencyStore.Claim pending = store.claim("run:create:u1", "k1", "h1",
                Duration.ofMinutes(5), now.plusSeconds(1));
        assertTrue(pending.replay());
        assertEquals(102, pending.record().orElseThrow().responseStatus());

        store.complete("run:create:u1", "k1", "h1", 202,
                Map.of("runId", "run-1"), now.plusSeconds(2));
        IdempotencyStore.Claim replay = store.claim("run:create:u1", "k1", "h1",
                Duration.ofMinutes(5), now.plusSeconds(3));
        assertTrue(replay.replay());
        assertEquals("run-1", replay.record().orElseThrow().responseBody().get("runId"));
    }

    @Test
    void sameKeyWithDifferentHashIsConflictAndExpiredCanBeReused() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        Instant now = Instant.parse("2026-09-03T00:00:00Z");
        assertTrue(store.claim("scope", "key", "hash-a", Duration.ofSeconds(1), now).accepted());
        assertTrue(store.claim("scope", "key", "hash-b", Duration.ofSeconds(1), now.plusMillis(100)).conflict());
        assertEquals(1, store.deleteExpired(now.plusSeconds(2), 10));
        assertTrue(store.claim("scope", "key", "hash-b", Duration.ofSeconds(1), now.plusSeconds(2)).accepted());
    }
}
