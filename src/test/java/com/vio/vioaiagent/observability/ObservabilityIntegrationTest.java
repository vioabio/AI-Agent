package com.vio.vioaiagent.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("可观测性集成测试")
class ObservabilityIntegrationTest {

    @Test @DisplayName("全链路: LogContext → MDC → 清理")
    void shouldCompleteFullTrace() {
        // 1. 创建日志上下文
        var ctx = new AgentLogContext("trace-001")
                .sessionId("session-1")
                .agentType("VioManus")
                .stepType("think")
                .stepIndex(1)
                .outcome("success");

        // 2. 注入 MDC
        ctx.injectMdc();
        assertEquals("trace-001", org.slf4j.MDC.get("traceId"));
        assertEquals("VioManus", org.slf4j.MDC.get("agentType"));

        // 3. JSON 输出
        String json = ctx.toJson();
        assertTrue(json.contains("trace-001"));
        assertTrue(json.contains("VioManus"));

        // 4. 清理 MDC
        AgentLogContext.clearMdc();
        assertNull(org.slf4j.MDC.get("traceId"));
    }

    @Test @DisplayName("指标 → snapshot → 验证增量")
    void shouldTrackMetricsCorrectly() {
        var metrics = new AgentMetrics();

        metrics.recordAgentExecution();
        metrics.recordToolCall(true);
        metrics.recordToolCall(true);
        metrics.recordToolCall(false);
        metrics.incrementActiveAgents();

        var snapshot = metrics.snapshot();
        assertEquals(1L, snapshot.get("agent.execution.count"));
        assertEquals(3L, snapshot.get("tool.call.count"));
        assertEquals(2L, snapshot.get("tool.call.success"));
        assertEquals(1L, snapshot.get("tool.call.failure"));
        assertEquals(1, metrics.getActiveAgentCount().get());
        assertNotNull(snapshot.get("tool.call.successRate"));
    }
}
