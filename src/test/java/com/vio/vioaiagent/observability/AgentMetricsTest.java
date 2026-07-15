package com.vio.vioaiagent.observability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Agent 指标收集器")
class AgentMetricsTest {
    private AgentMetrics metrics;

    @BeforeEach void setUp() { metrics = new AgentMetrics(); }

    @Test @DisplayName("应正确递增计数器")
    void shouldIncrementCounters() {
        metrics.recordAgentExecution();
        metrics.recordAgentExecution();
        metrics.recordToolCall(true);
        metrics.recordToolCall(false);

        var snapshot = metrics.snapshot();
        assertEquals(2L, snapshot.get("agent.execution.count"));
        assertEquals(2L, snapshot.get("tool.call.count"));
        assertEquals(1L, snapshot.get("tool.call.success"));
        assertEquals(1L, snapshot.get("tool.call.failure"));
    }

    @Test @DisplayName("活跃 Agent 计数")
    void shouldTrackActiveAgents() {
        assertEquals(0, metrics.getActiveAgentCount().get());
        metrics.incrementActiveAgents();
        metrics.incrementActiveAgents();
        assertEquals(2, metrics.getActiveAgentCount().get());
        metrics.decrementActiveAgents();
        assertEquals(1, metrics.getActiveAgentCount().get());
    }

    @Test @DisplayName("成功率计算")
    void shouldCalculateSuccessRate() {
        metrics.recordToolCall(true);
        metrics.recordToolCall(true);
        metrics.recordToolCall(false);
        var snapshot = metrics.snapshot();
        assertTrue(snapshot.get("tool.call.successRate").toString().contains("66"));
    }

    @Test @DisplayName("reset 应归零所有计数器")
    void shouldResetAllCounters() {
        metrics.recordAgentExecution();
        metrics.recordError();
        metrics.reset();
        var snapshot = metrics.snapshot();
        assertEquals(0L, snapshot.get("agent.execution.count"));
        assertEquals(0L, snapshot.get("error.count"));
    }
}
