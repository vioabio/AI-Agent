package com.vio.vioaiagent.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M4 回归：审批过期必须自动通知 Run 收敛，而不是静默从内存表删除。 */
class HitlExpiryTest {

    @Test
    void expiryNotifiesListenerAndWritesAudit() throws Exception {
        List<HitlAuditEvent> audits = new ArrayList<>();
        CountDownLatch expired = new CountDownLatch(1);
        try (AutoCloseableManager manager = new AutoCloseableManager(
                new HitlManager(audits::add, Duration.ofMillis(30)))) {
            manager.value.registerExpiryListener(pending -> expired.countDown());
            HitlManager.ApprovalDecision waiting = manager.value.evaluate(
                    "user-1", "run-1", "writeFile", "v1", "hash-expiry", true);
            assertTrue(waiting.waiting());
            assertTrue(expired.await(2, TimeUnit.SECONDS), "审批过期应触发生命周期回调");
            assertEquals(null, manager.value.getPending(waiting.approvalId()));
            assertTrue(audits.stream().anyMatch(a -> "EXPIRED".equals(a.action())));
        }
    }

    @Test
    void durableExpiryBatchNotifiesEverySettledApproval() {
        List<String> expired = new ArrayList<>();
        InMemoryHitlApprovalRepository repository = new InMemoryHitlApprovalRepository();
        HitlManager manager = new HitlManager(HitlAuditSink.noop());
        manager.setApprovalRepository(repository);
        manager.registerExpiryListener(item -> expired.add(item.approvalId()));
        Instant now = Instant.now();
        try {
            String firstId = "approval-batch-1";
            String secondId = "approval-batch-2";
            repository.createOrGet(new HitlApproval(firstId, "u-batch", "run-batch-1", "write_file",
                    "1.0.0", "digest-1", "MEDIUM", now.minusMillis(1), HitlApproval.Decision.PENDING,
                    null, null, null, null, null, 0, now.minusSeconds(2), now.minusSeconds(2)), now);
            repository.createOrGet(new HitlApproval(secondId, "u-batch", "run-batch-2", "write_file",
                    "1.0.0", "digest-2", "MEDIUM", now.minusMillis(1), HitlApproval.Decision.PENDING,
                    null, null, null, null, null, 0, now.minusSeconds(2), now.minusSeconds(2)), now);
            // Force both rows into one due batch without relying on scheduler timing.
            manager.expirePending();

            assertTrue(expired.contains(firstId));
            assertTrue(expired.contains(secondId));
            assertEquals(2, expired.size(), "批量 expireDue 的每一行都应触发一次回调");
        } finally {
            manager.shutdown();
        }
    }

    /** 让测试显式关闭 daemon scheduler，避免长时间运行套件积累线程。 */
    private static final class AutoCloseableManager implements AutoCloseable {
        private final HitlManager value;

        private AutoCloseableManager(HitlManager value) {
            this.value = value;
        }

        @Override
        public void close() {
            value.shutdown();
        }
    }
}
