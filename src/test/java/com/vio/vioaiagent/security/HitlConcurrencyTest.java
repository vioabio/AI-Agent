package com.vio.vioaiagent.security;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M4 HITL 回归：同一工具调用只生成一个审批，决定只能成功一次。 */
class HitlConcurrencyTest {

    @Test
    void concurrentEvaluateSharesOneApproval() throws Exception {
        List<HitlAuditEvent> audits = java.util.Collections.synchronizedList(new ArrayList<>());
        HitlManager manager = new HitlManager(audits::add);
        int count = 24;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<HitlManager.ApprovalDecision>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    return manager.evaluate("user-1", "run-1", "writeFile", "v1", "hash-1", true);
                }));
            }
            gate.countDown();
            String approvalId = null;
            for (Future<HitlManager.ApprovalDecision> future : futures) {
                HitlManager.ApprovalDecision decision = future.get();
                assertTrue(decision.waiting());
                if (approvalId == null) approvalId = decision.approvalId();
                assertEquals(approvalId, decision.approvalId());
            }
            assertNotNull(approvalId);
            assertEquals(1, audits.stream().filter(a -> "REQUIRED".equals(a.action())).count());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentDecideCannotReverseFirstDecision() throws Exception {
        List<HitlAuditEvent> audits = java.util.Collections.synchronizedList(new ArrayList<>());
        HitlManager manager = new HitlManager(audits::add);
        HitlManager.ApprovalDecision waiting = manager.evaluate(
                "user-1", "run-1", "writeFile", "v1", "hash-1", true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            Future<HitlManager.ApprovalDecision> approved = pool.submit(() -> {
                gate.await();
                return manager.decide(waiting.approvalId(), "user-1", "run-1", true, null);
            });
            Future<HitlManager.ApprovalDecision> denied = pool.submit(() -> {
                gate.await();
                return manager.decide(waiting.approvalId(), "user-1", "run-1", false, "no");
            });
            gate.countDown();
            approved.get();
            denied.get();
            HitlManager.PendingApproval finalState = manager.getApproval(waiting.approvalId());
            assertNotNull(finalState);
            assertTrue(finalState.status() == HitlManager.ApprovalStatus.APPROVED
                    || finalState.status() == HitlManager.ApprovalStatus.DENIED);
            assertEquals(1, audits.stream().filter(a -> "APPROVED".equals(a.action())
                    || "DENIED".equals(a.action())).count());
        } finally {
            pool.shutdownNow();
        }
    }
}
