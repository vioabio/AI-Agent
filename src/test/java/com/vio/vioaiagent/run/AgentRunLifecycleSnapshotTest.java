package com.vio.vioaiagent.run;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** SQL rollback must restore the aggregate's lifecycle before failure handling reuses it. */
class AgentRunLifecycleSnapshotTest {

    @Test
    void completionRollbackRestoresRunningLifecycleAndAllowsFailureWithOriginalVersion() {
        Instant startedAt = Instant.parse("2026-10-12T01:00:01Z");
        AgentRun run = restoredRun(AgentRunStatus.RUNNING, startedAt);
        List<Object> frozen = frozenContext(run);
        synchronized (run) {
            AgentRun.LifecycleSnapshot before = run.lifecycleSnapshot();
            assertTrue(run.complete());
            run.markPersistedVersion(8); // JDBC CAS succeeded inside an uncommitted transaction.
            assertEquals(AgentRunStatus.COMPLETED, run.status());
            assertNotNull(run.finishedAt());
            assertEquals(8, run.version());

            run.restoreLifecycle(before); // A later event/outbox write failed and SQL rolled back.
            assertEquals(AgentRunStatus.RUNNING, run.status());
            assertEquals(startedAt, run.startedAt());
            assertNull(run.finishedAt());
            assertEquals(7, run.version());
            assertEquals(frozen, frozenContext(run));

            assertTrue(run.fail(), "rolled-back COMPLETED must not suppress the genuine failure path");
            run.markPersistedVersion(8);
            assertEquals(AgentRunStatus.FAILED, run.status());
            assertEquals(8, run.version(), "the next CAS must use the version that actually survived rollback");
            assertEquals(frozen, frozenContext(run));
        }
    }

    @Test
    void rollbackRestoresUnsetLifecycleTimestampsAndFrozenLineageAfterMultipleTransitions() {
        AgentRun run = restoredRun(AgentRunStatus.CREATED, null);
        List<Object> frozen = frozenContext(run);
        synchronized (run) {
            AgentRun.LifecycleSnapshot before = run.lifecycleSnapshot();
            assertTrue(run.transitionTo(AgentRunStatus.RUNNING));
            run.markPersistedVersion(8);
            assertNotNull(run.startedAt());
            assertTrue(run.complete());
            run.markPersistedVersion(9);
            assertNotNull(run.finishedAt());

            run.restoreLifecycle(before);
            assertEquals(AgentRunStatus.CREATED, run.status());
            assertNull(run.startedAt());
            assertNull(run.finishedAt());
            assertEquals(7, run.version());
            assertEquals(frozen, frozenContext(run));
        }
    }

    private static AgentRun restoredRun(AgentRunStatus status, Instant startedAt) {
        return AgentRun.restore("run-lifecycle", "session", "conversation", "owner", "frozen prompt",
                "sv-1", "singles", "rules-1", "zh-CN", "dlc-1", "data-1", "knowledge-1", "index-1", "v1.1",
                "trace", "idempotency", Instant.parse("2026-10-12T01:00:00Z"),
                Instant.parse("2026-10-12T02:00:00Z"), status, startedAt, null, 7);
    }

    private static List<Object> frozenContext(AgentRun run) {
        return List.of(run.id(), run.sessionId(), run.conversationId(), run.userId(), run.prompt(),
                run.gameReleaseId(), run.battleFormat(), run.rulesetVersionId(), run.language(), run.dlcId(),
                run.dataReleaseId(), run.knowledgeReleaseId(), run.indexReleaseId(), run.contractVersion(),
                run.traceId(), run.idempotencyKey(), run.createdAt(), run.deadlineAt());
    }
}
