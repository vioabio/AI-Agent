package com.vio.vioaiagent.multiagent;

import com.vio.vioaiagent.multiagent.model.ExecutionPlan;
import com.vio.vioaiagent.multiagent.model.Task;
import com.vio.vioaiagent.multiagent.model.TaskResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AgentOrchestrator 调度")
class OrchestratorTest {

    @Test @DisplayName("单任务计划 → 1 层 1 Worker")
    void shouldExecuteSingleTask() {
        var orchestrator = new AgentOrchestrator(null, new org.springframework.ai.tool.ToolCallback[0],
                Executors.newSingleThreadExecutor());
        ExecutionPlan plan = new ExecutionPlan(List.of(Task.of("t1", "test")), "test");

        List<TaskResult> results = orchestrator.execute(plan);
        assertEquals(1, results.size());
    }

    @Test @DisplayName("多任务无依赖 → 1 层并行")
    void shouldParallelizeIndependentTasks() {
        var orchestrator = new AgentOrchestrator(null, new org.springframework.ai.tool.ToolCallback[0],
                Executors.newFixedThreadPool(4));
        ExecutionPlan plan = new ExecutionPlan(List.of(
                Task.of("t1", "a"), Task.of("t2", "b"), Task.of("t3", "c")), "test");

        long start = System.currentTimeMillis();
        List<TaskResult> results = orchestrator.execute(plan);
        long duration = System.currentTimeMillis() - start;
        assertEquals(3, results.size());
        assertTrue(duration < 2000, "并行应在 < 2s 完成");
    }
}
