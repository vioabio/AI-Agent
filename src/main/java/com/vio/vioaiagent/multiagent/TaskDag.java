package com.vio.vioaiagent.multiagent;

import com.vio.vioaiagent.multiagent.model.ExecutionPlan;
import com.vio.vioaiagent.multiagent.model.Task;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 任务 DAG（有向无环图）— 管理任务间的依赖关系.
 *
 * <p>核心方法:
 * <ul>
 *   <li>{@link #toLevels()} — Kahn 算法拓扑排序, 输出分层列表</li>
 *   <li>{@link #hasCycle()} — 循环依赖检测</li>
 * </ul>
 *
 * <p>分层结果: 同一层内的任务无相互依赖, 可并行执行.
 * 不同层之间存在依赖关系, 必须先完成前层再执行后层.
 *
 * @author vio
 */
@Slf4j
public class TaskDag {

    /** 任务 ID → 任务 */
    private final Map<String, Task> taskMap;
    /** 任务 ID → 前置任务 ID 集合 (入边) */
    private final Map<String, Set<String>> inEdges;
    /** 任务 ID → 后继任务 ID 集合 (出边) */
    private final Map<String, Set<String>> outEdges;

    /**
     * 从执行计划构建 DAG.
     */
    public TaskDag(ExecutionPlan plan) {
        this.taskMap = new LinkedHashMap<>();
        this.inEdges = new HashMap<>();
        this.outEdges = new HashMap<>();

        for (Task task : plan.tasks()) {
            taskMap.put(task.id(), task);
            inEdges.putIfAbsent(task.id(), new LinkedHashSet<>());
            outEdges.putIfAbsent(task.id(), new LinkedHashSet<>());

            for (String dep : task.dependsOn()) {
                inEdges.get(task.id()).add(dep);
                outEdges.computeIfAbsent(dep, k -> new LinkedHashSet<>()).add(task.id());
                // 确保依赖节点也注册
                taskMap.putIfAbsent(dep, Task.of(dep, "(前置依赖)"));
                inEdges.putIfAbsent(dep, new LinkedHashSet<>());
            }
        }
    }

    /**
     * Kahn 算法拓扑排序 → 分层执行计划.
     *
     * <p>算法步骤:
     * <ol>
     *   <li>计算每个节点的入度</li>
     *   <li>入度为 0 的节点进入第 0 层</li>
     *   <li>逐层处理: 移除当前层节点 → 后继入度减 1 → 入度为 0 的新节点进下一层</li>
     * </ol>
     *
     * @return 分层任务列表（每层内任务可并行）
     * @throws IllegalStateException 如果检测到循环依赖
     */
    public List<List<Task>> toLevels() {
        // 1. 计算入度
        Map<String, Integer> inDegree = new HashMap<>();
        for (String taskId : taskMap.keySet()) {
            inDegree.put(taskId, inEdges.getOrDefault(taskId, Set.of()).size());
        }

        // 2. 入度为 0 的节点作为第 0 层
        List<List<Task>> levels = new ArrayList<>();
        Queue<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        int processedCount = 0;
        while (!queue.isEmpty()) {
            int levelSize = queue.size();
            List<Task> currentLevel = new ArrayList<>();

            for (int i = 0; i < levelSize; i++) {
                String taskId = queue.poll();
                Task task = taskMap.get(taskId);
                if (task != null) {
                    currentLevel.add(task);
                }
                processedCount++;

                // 后继节点入度减 1
                for (String next : outEdges.getOrDefault(taskId, Set.of())) {
                    int newDegree = inDegree.merge(next, -1, Integer::sum);
                    if (newDegree == 0) {
                        queue.add(next);
                    }
                }
            }
            if (!currentLevel.isEmpty()) {
                levels.add(currentLevel);
            }
        }

        // 3. 循环检测
        if (processedCount != taskMap.size()) {
            throw new IllegalStateException("任务 DAG 存在循环依赖! 已处理 " + processedCount
                    + " / " + taskMap.size() + " 个任务");
        }

        log.info("DAG 拓扑排序完成: {} 个任务, {} 层, 最大并行度={}",
                taskMap.size(), levels.size(),
                levels.stream().mapToInt(List::size).max().orElse(0));
        return levels;
    }

    /** 是否有循环依赖 */
    public boolean hasCycle() {
        try {
            toLevels();
            return false;
        } catch (IllegalStateException e) {
            return true;
        }
    }

    /** 任务总数 */
    public int taskCount() { return taskMap.size(); }
}
