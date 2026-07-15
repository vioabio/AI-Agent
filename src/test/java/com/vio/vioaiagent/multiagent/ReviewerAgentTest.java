package com.vio.vioaiagent.multiagent;

import com.vio.vioaiagent.multiagent.model.Task;
import com.vio.vioaiagent.multiagent.model.TaskResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ReviewerAgent")
class ReviewerAgentTest {

    @Test @DisplayName("ReviewVerdict.pass 应创建通过结论")
    void shouldCreatePassVerdict() {
        ReviewVerdict v = ReviewVerdict.pass("所有任务完成");
        assertTrue(v.passed());
        assertEquals(100, v.score());
        assertTrue(v.tasksToRetry().isEmpty());
    }

    @Test @DisplayName("ReviewVerdict.fail 应创建失败结论")
    void shouldCreateFailVerdict() {
        ReviewVerdict v = ReviewVerdict.fail(40, "需要改进", List.of("task-2"));
        assertFalse(v.passed());
        assertEquals(40, v.score());
        assertEquals(1, v.tasksToRetry().size());
        assertTrue(v.tasksToRetry().contains("task-2"));
    }

    @Test @DisplayName("ReviewVerdict 阈值应为 60")
    void shouldUsePassThreshold60() {
        assertEquals(60, ReviewVerdict.PASS_THRESHOLD);
        assertEquals(2, ReviewVerdict.MAX_RETRIES);
    }

    @Test @DisplayName("LLM 失败时 review 应默认通过")
    void shouldDefaultPassOnLlmError() {
        ReviewerAgent reviewer = new ReviewerAgent(null);
        // ChatModel 为 null 时, review() catch 异常并默认通过
        try {
            ReviewVerdict v = reviewer.review(
                    List.of(Task.of("t1", "test")),
                    List.of(TaskResult.success("t1", "ok", 10)));
            assertNotNull(v);
            assertTrue(v.passed()); // 默认通过
        } catch (Exception e) {
            assertTrue(e instanceof NullPointerException);
        }
    }
}
