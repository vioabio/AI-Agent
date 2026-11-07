package com.vio.vioaiagent.multiagent;

import com.vio.vioaiagent.multiagent.model.ExecutionPlan;
import com.vio.vioaiagent.multiagent.model.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DAG 拓扑排序")
class TaskDagTest {

    @Test @DisplayName("无依赖任务 → 1 层全并行")
    void shouldPutAllInOneLevel() {
        ExecutionPlan plan = new ExecutionPlan(List.of(
                Task.of("t1", "搜索天气"), Task.of("t2", "搜索新闻"), Task.of("t3", "生成PDF")), "test");
        TaskDag dag = new TaskDag(plan);
        List<List<Task>> levels = dag.toLevels();
        assertEquals(1, levels.size());
        assertEquals(3, levels.get(0).size());
    }

    @Test @DisplayName("线性依赖 t1→t2→t3 → 3 层")
    void shouldLinearize() {
        ExecutionPlan plan = new ExecutionPlan(List.of(
                new Task("t1", "搜索", List.of(), null, null),
                new Task("t2", "分析", List.of("t1"), null, null),
                new Task("t3", "报告", List.of("t2"), null, null)), "test");
        TaskDag dag = new TaskDag(plan);
        List<List<Task>> levels = dag.toLevels();
        assertEquals(3, levels.size());
        assertEquals(1, levels.get(0).size()); assertEquals("t1", levels.get(0).get(0).id());
        assertEquals(1, levels.get(1).size()); assertEquals("t2", levels.get(1).get(0).id());
        assertEquals(1, levels.get(2).size()); assertEquals("t3", levels.get(2).get(0).id());
    }

    @Test @DisplayName("混合依赖")
    void shouldHandleMixedDeps() {
        ExecutionPlan plan = new ExecutionPlan(List.of(
                new Task("t1", "搜索", List.of(), null, null),
                new Task("t2", "抓取", List.of(), null, null),
                new Task("t3", "分析", List.of("t1", "t2"), null, null)), "test");
        TaskDag dag = new TaskDag(plan);
        List<List<Task>> levels = dag.toLevels();
        assertEquals(2, levels.size());
        assertEquals(2, levels.get(0).size()); // t1, t2 并行
        assertEquals(1, levels.get(1).size()); // t3 等待两者
    }

    @Test @DisplayName("循环依赖应抛异常")
    void shouldDetectCycle() {
        ExecutionPlan plan = new ExecutionPlan(List.of(
                new Task("t1", "A", List.of("t2"), null, null),
                new Task("t2", "B", List.of("t1"), null, null)), "test");
        TaskDag dag = new TaskDag(plan);
        assertThrows(IllegalStateException.class, dag::toLevels);
        assertTrue(dag.hasCycle());
    }

    @Test @DisplayName("空计划")
    void shouldHandleEmptyPlan() {
        ExecutionPlan plan = new ExecutionPlan(List.of(), "test");
        TaskDag dag = new TaskDag(plan);
        assertTrue(dag.toLevels().isEmpty());
    }
}
