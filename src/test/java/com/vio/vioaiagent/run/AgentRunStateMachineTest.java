package com.vio.vioaiagent.run;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRunStateMachineTest {

    @Test
    void shouldAllowOnlyOneTerminalTransition() {
        AgentRun run = AgentRun.create("run-1", "session-1", "conversation-1", "user-1", "hello",
                "sv-1", "singles", "rules-1", "zh-CN", "trace-1", null,
                Instant.now().plusSeconds(60));

        assertTrue(run.transitionTo(AgentRunStatus.RUNNING));
        assertTrue(run.complete());
        assertFalse(run.cancel());
        assertFalse(run.transitionTo(AgentRunStatus.RUNNING));
    }
}

