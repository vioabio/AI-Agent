package com.vio.vioaiagent.multiagent;

import com.vio.vioaiagent.multiagent.model.Task;
import com.vio.vioaiagent.multiagent.model.TaskResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WorkerAgent")
class WorkerAgentTest {

    @Test @DisplayName("null ChatModel 构造应抛异常")
    void shouldThrowForNullChatModel() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorkerAgent(null, new ToolCallback[0]));
    }

    @Test @DisplayName("TaskResult.success 工厂方法")
    void shouldCreateSuccessResult() {
        TaskResult r = TaskResult.success("t1", "output", 100);
        assertEquals(TaskResult.Status.SUCCESS, r.status());
        assertEquals("output", r.output());
        assertNull(r.errorMessage());
    }

    @Test @DisplayName("TaskResult.failed 工厂方法")
    void shouldCreateFailedResult() {
        TaskResult r = TaskResult.failed("t1", "boom", 50);
        assertEquals(TaskResult.Status.FAILED, r.status());
        assertEquals("boom", r.errorMessage());
    }
}
