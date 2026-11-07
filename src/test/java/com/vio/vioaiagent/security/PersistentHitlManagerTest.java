package com.vio.vioaiagent.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class PersistentHitlManagerTest {
    @Test
    void persistentApprovalSurvivesManagerCacheAndFirstDecisionWins() {
        InMemoryHitlApprovalRepository repository = new InMemoryHitlApprovalRepository();
        HitlManager manager = new HitlManager(HitlAuditSink.noop(), Duration.ofMinutes(5));
        manager.setApprovalRepository(repository);

        var waiting = manager.evaluate("u-1", "run-1", "write_file", "1.0.0", "digest-1", true);
        assertTrue(waiting.waiting());
        assertNotNull(waiting.approvalId());
        assertTrue(manager.getPending(waiting.approvalId()) != null);

        var approved = manager.decide(waiting.approvalId(), "u-1", "run-1", true, "ok");
        assertTrue(approved.allowed());
        var replayEvaluation = manager.evaluate("u-1", "run-1", "write_file", "1.0.0", "digest-1", true);
        assertTrue(replayEvaluation.allowed(), "已批准的同一幂等调用应可重放");
        // A second, opposite request must not reverse the persisted decision.
        var replay = manager.decide(waiting.approvalId(), "u-1", "run-1", false, "late");
        assertFalse(replay.allowed());
        assertTrue(replay.reason().contains("批准"));
    }

    @Test
    void persistentApprovalRejectsCrossRunDecision() {
        InMemoryHitlApprovalRepository repository = new InMemoryHitlApprovalRepository();
        HitlManager manager = new HitlManager(HitlAuditSink.noop());
        manager.setApprovalRepository(repository);
        var waiting = manager.evaluate("u-1", "run-1", "write_file", "1.0.0", "digest-2", true);
        var result = manager.decide(waiting.approvalId(), "u-1", "run-2", true, null);
        assertFalse(result.allowed());
        assertTrue(result.reason().contains("上下文") || result.reason().contains("不存在"));
    }

    @Test
    void durableReadRefreshesStalePendingCacheAfterExternalDecision() {
        InMemoryHitlApprovalRepository repository = new InMemoryHitlApprovalRepository();
        HitlManager manager = new HitlManager(HitlAuditSink.noop());
        manager.setApprovalRepository(repository);
        try {
            HitlManager.ApprovalDecision waiting = manager.evaluate(
                    "u-refresh", "run-refresh", "write_file", "1.0.0", "digest-refresh", true);
            assertEquals(HitlManager.ApprovalStatus.PENDING,
                    manager.getApproval(waiting.approvalId()).status());

            // Simulate another instance deciding the shared row after this
            // manager cached the PENDING snapshot.
            repository.decide(waiting.approvalId(), "u-refresh", "run-refresh", true,
                    "operator", "approved", java.time.Instant.now());

            assertEquals(HitlManager.ApprovalStatus.APPROVED,
                    manager.getApproval(waiting.approvalId()).status(),
                    "durable read must not return a stale local PENDING snapshot");
        } finally {
            manager.shutdown();
        }
    }
}
