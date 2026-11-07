package com.vio.vioaiagent.run;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class DurableRunStateTest {

    @Test
    void checkpointUsesCasAndLatestStep() {
        InMemoryDurableRunStateRepository repository = new InMemoryDurableRunStateRepository();
        AgentCheckpoint first = AgentCheckpoint.initial("run-1", "executor-v1", "hash-1", "dispatch");
        AgentCheckpoint persisted = repository.save(first);
        assertEquals(1, persisted.version());
        AgentCheckpoint next = new AgentCheckpoint("run-1", 1, "executor-v1", "hash-1", 3,
                "commit", "worker-1", Instant.now().plusSeconds(30), Instant.now(), Instant.now(),
                persisted.version());
        assertEquals(2, repository.save(next).version());
        assertEquals(1, repository.findLatest("run-1").orElseThrow().stepNo());
        assertThrows(IllegalStateException.class, () -> repository.save(next));
    }

    @Test
    void leaseOnlyAllowsOwnerHeartbeatAndExpiredTakeover() throws Exception {
        InMemoryDurableRunStateRepository repository = new InMemoryDurableRunStateRepository();
        assertTrue(repository.acquireLease("run-1", "a", "token-a", Duration.ofMillis(20)).isPresent());
        assertTrue(repository.acquireLease("run-1", "b", "token-b", Duration.ofSeconds(1)).isEmpty());
        assertTrue(repository.heartbeat("run-1", "a", "token-a", Duration.ofSeconds(1)).isPresent());
        assertFalse(repository.releaseLease("run-1", "a", "wrong"));
        assertTrue(repository.releaseLease("run-1", "a", "token-a"));
        assertTrue(repository.acquireLease("run-1", "b", "token-b", Duration.ofSeconds(1)).isPresent());
    }

    @Test
    void effectLedgerDeduplicatesFullNonNullKey() {
        InMemoryDurableRunStateRepository repository = new InMemoryDurableRunStateRepository();
        ToolEffectLedgerEntry entry = ToolEffectLedgerEntry.started("run-1", 0, "fact", "v1", "idem-1", "param-1");
        assertTrue(repository.reserve(entry));
        assertFalse(repository.reserve(entry));
        ToolEffectLedgerEntry completed = entry.complete(ToolEffectStatus.SUCCEEDED, "digest", null, null);
        assertEquals(ToolEffectStatus.SUCCEEDED, repository.complete(completed).status());
        assertEquals(ToolEffectStatus.SUCCEEDED,
                repository.find("run-1", 0, "fact", "v1", "idem-1", "param-1").orElseThrow().status());
    }

    @Test
    void effectLedgerCanDetectSameIdempotencyKeyBoundToDifferentParameters() {
        InMemoryDurableRunStateRepository repository = new InMemoryDurableRunStateRepository();
        ToolEffectLedgerEntry entry = ToolEffectLedgerEntry.started("run-1", 4, "write", "v1", "idem-1", "param-1");
        assertTrue(repository.reserve(entry));
        ToolEffectLedgerEntry found = repository.findByIdempotencyKey("run-1", 4, "write", "v1", "idem-1").orElseThrow();
        assertEquals("param-1", found.parameterHash());
    }

    @Test
    void unknownEffectRequiresExplicitManualResolution() {
        InMemoryDurableRunStateRepository repository = new InMemoryDurableRunStateRepository();
        ToolEffectLedgerEntry started = ToolEffectLedgerEntry.started("run-1", 5, "write", "v1", "idem-1", "param-1");
        assertTrue(repository.reserve(started));
        ToolEffectLedgerEntry unknown = started.complete(ToolEffectStatus.UNKNOWN, "uncertain", null, null);
        assertEquals(ToolEffectStatus.UNKNOWN, repository.complete(unknown).status());
        ToolEffectLedgerEntry resolved = repository.resolveUnknown(unknown, ToolEffectStatus.FAILED, "manual-failed", "operator");
        assertEquals(ToolEffectStatus.FAILED, resolved.status());
        assertThrows(IllegalStateException.class,
                () -> repository.resolveUnknown(unknown, ToolEffectStatus.SUCCEEDED, "late", null));
    }
}
