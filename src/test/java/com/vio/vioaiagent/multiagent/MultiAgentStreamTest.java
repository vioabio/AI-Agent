package com.vio.vioaiagent.multiagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MultiAgent SSE 流式")
class MultiAgentStreamTest {

    @Test @DisplayName("executeStream 应返回 SseEmitter")
    void shouldReturnSseEmitter() {
        var orchestrator = new MultiAgentOrchestrator(null,
                new org.springframework.ai.tool.ToolCallback[0],
                java.util.concurrent.Executors.newSingleThreadExecutor());

        var emitter = orchestrator.executeStream("test request");
        assertNotNull(emitter);
    }

    @Test @DisplayName("OrchestrationResult 工厂方法")
    void shouldCreateOrchestrationResult() {
        var result = new OrchestrationResult(
                java.util.List.of(), 1000, 85, "final output", 0);
        assertEquals(85, result.reviewScore());
        assertEquals("final output", result.finalOutput());
        assertEquals(0, result.retriesTriggered());
        assertTrue(result.isAllSuccess());
    }

    @Test @DisplayName("OrchestrationResult 有失败任务时 isAllSuccess=false")
    void shouldDetectFailure() {
        var failed = new com.vio.vioaiagent.multiagent.model.TaskResult(
                "t1", com.vio.vioaiagent.multiagent.model.TaskResult.Status.FAILED,
                null, "error", 50);
        var result = new OrchestrationResult(
                java.util.List.of(failed), 100, 30, "failed", 2);
        assertFalse(result.isAllSuccess());
        assertEquals(2, result.retriesTriggered());
    }
}
