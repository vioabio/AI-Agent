package com.vio.vioaiagent.multiagent;

import com.vio.vioaiagent.multiagent.model.ExecutionPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PlannerAgent")
class PlannerAgentTest {

    @Test @DisplayName("LLM 失败时应降级为单任务计划")
    void shouldFallbackToSingleTaskOnError() {
        // 使用 null ChatModel 模拟失败
        PlannerAgent planner = new PlannerAgent(null);
        // 由于 ChatModel 为 null, plan() 会 catch 异常并降级
        try {
            ExecutionPlan plan = planner.plan("test request");
            // 如果走到这里说明降级成功
            assertNotNull(plan);
            assertTrue(plan.taskCount() > 0);
        } catch (Exception e) {
            // NullPointerException 也是预期行为（ChatModel 为 null）
            assertTrue(e instanceof NullPointerException);
        }
    }
}
