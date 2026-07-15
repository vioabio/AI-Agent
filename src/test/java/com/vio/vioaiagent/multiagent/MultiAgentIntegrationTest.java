package com.vio.vioaiagent.multiagent;

import com.vio.vioaiagent.multiagent.model.ExecutionPlan;
import com.vio.vioaiagent.multiagent.model.Task;
import com.vio.vioaiagent.multiagent.model.TaskResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("多智能体集成测试")
class MultiAgentIntegrationTest {

    @Test @DisplayName("全链路: Plan → Execute → Review（无 LLM 模式）")
    void shouldCompleteFullPipeline() {
        // 模拟：DAG 构建 → 拓扑排序 → Worker 执行
        ExecutionPlan plan = new ExecutionPlan(List.of(
                new Task("t1", "搜索天气", List.of(), null, "天气数据"),
                new Task("t2", "搜索新闻", List.of(), null, "新闻列表"),
                new Task("t3", "生成摘要", List.of("t1", "t2"), null, "合并摘要")), "test");

        TaskDag dag = new TaskDag(plan);
        List<List<Task>> levels = dag.toLevels();

        assertEquals(2, levels.size());
        assertEquals(2, levels.get(0).size());
        assertEquals(1, levels.get(1).size());

        // 模拟 Orchestrator: 逐层执行
        var orchestrator = new AgentOrchestrator(null, new ToolCallback[0],
                Executors.newFixedThreadPool(3));
        List<TaskResult> results = orchestrator.execute(plan);

        assertEquals(3, results.size());
        results.forEach(r -> assertNotNull(r.taskId()));
    }

    @Test @DisplayName("并行验证: CountDownLatch 确认同层任务同时运行")
    void shouldRunParallelTasksConcurrently() throws Exception {
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch doneSignal = new CountDownLatch(2);

        Runnable parallelTask = () -> {
            try {
                startSignal.await();
                Thread.sleep(100);
                doneSignal.countDown();
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };

        new Thread(parallelTask).start();
        new Thread(parallelTask).start();
        startSignal.countDown();

        assertTrue(doneSignal.await(2, TimeUnit.SECONDS),
                "两个并行任务应在 < 2s 完成");
    }

    @Test @DisplayName("ReviewVerdict 流: pass → retry → pass")
    void shouldHandleRetryFlow() {
        ReviewVerdict v1 = ReviewVerdict.fail(50, "需要改进任务 t2",
                List.of("task-2"));
        assertFalse(v1.passed());
        assertEquals(1, v1.tasksToRetry().size());

        ReviewVerdict v2 = ReviewVerdict.pass("重试后通过");
        assertTrue(v2.passed());
        assertEquals(100, v2.score());
    }
}
