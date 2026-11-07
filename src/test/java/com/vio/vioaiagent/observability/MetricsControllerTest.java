package com.vio.vioaiagent.observability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("指标控制器")
class MetricsControllerTest {
    private AgentMetrics metrics;

    @BeforeEach void setUp() { metrics = new AgentMetrics(); }

    @Test @DisplayName("snapshot 应包含所有必需字段")
    void shouldContainAllFields() {
        metrics.recordAgentExecution();
        var snapshot = metrics.snapshot();
        assertTrue(snapshot.containsKey("agent.execution.count"));
        assertTrue(snapshot.containsKey("tool.call.count"));
        assertTrue(snapshot.containsKey("agent.active.count"));
        assertTrue(snapshot.containsKey("tokens.consumed"));
    }

    @Test @DisplayName("初始状态成功率应为 N/A")
    void shouldShowNAForInitialSuccessRate() {
        var snapshot = metrics.snapshot();
        assertEquals("N/A", snapshot.get("tool.call.successRate"));
    }
}
