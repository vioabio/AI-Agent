package com.vio.vioaiagent.run;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** N2 checkpoint 恢复策略：只有未开始 dispatch 才允许自动重放。 */
class RunRecoveryPolicyTest {

    private final RunRecoveryPolicy policy = new RunRecoveryPolicy();

    @Test
    void dispatchCheckpointCanResume() {
        AgentCheckpoint checkpoint = checkpoint(0, "dispatch");

        RunRecoveryPolicy.Decision decision = policy.decide(checkpoint);

        assertTrue(decision.canResume());
        assertEquals(RunRecoveryPolicy.Action.RESUME_DISPATCH, decision.action());
        assertEquals("DISPATCH_NOT_STARTED", decision.reason());
    }

    @Test
    void checkpointAfterExecutionFailsClosed() {
        for (String action : new String[]{"execute-answer", "commit-answer", "finished"}) {
            RunRecoveryPolicy.Decision decision = policy.decide(checkpoint(1, action));

            assertFalse(decision.canResume(), action);
            assertEquals(RunRecoveryPolicy.Action.FAIL_CLOSED, decision.action());
            assertEquals("CHECKPOINT_AFTER_NON_REPLAYABLE_STEP", decision.reason());
        }
    }

    @Test
    void missingCheckpointFailsClosed() {
        RunRecoveryPolicy.Decision decision = policy.decide(null);

        assertFalse(decision.canResume());
        assertEquals("CHECKPOINT_MISSING", decision.reason());
    }

    private AgentCheckpoint checkpoint(int stepNo, String nextAction) {
        return new AgentCheckpoint("run-1", stepNo, "executor-v1", "context-hash", 0,
                nextAction, null, null, null, Instant.now(), 0);
    }
}
