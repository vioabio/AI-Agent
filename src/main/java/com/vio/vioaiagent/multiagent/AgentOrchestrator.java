package com.vio.vioaiagent.multiagent;

import com.vio.vioaiagent.multiagent.model.ExecutionPlan;
import com.vio.vioaiagent.multiagent.model.Task;
import com.vio.vioaiagent.multiagent.model.TaskResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Agent 编排者 — DAG 并行调度引擎.
 *
 * <p>核心流程:
 * <ol>
 *   <li>从 ExecutionPlan 构建 TaskDag</li>
 *   <li>Kahn 算法拓扑排序 → 分层</li>
 *   <li>逐层执行: 同层内所有 Task 并行提交到 Worker</li>
 *   <li>等待当前层全部完成后, 将结果注入上下文, 执行下一层</li>
 * </ol>
 *
 * @author vio
 */
@Slf4j
public class AgentOrchestrator {

    private final ChatModel chatModel;
    private final ToolCallback[] tools;
    private final Executor executor;

    public AgentOrchestrator(ChatModel chatModel, ToolCallback[] tools, Executor executor) {
        this.chatModel = chatModel;
        this.tools = tools;
        this.executor = executor;
    }

    /**
     * 执行计划 — 并行调度 Worker 执行.
     *
     * @param plan 执行计划
     * @return 所有任务结果
     */
    public List<TaskResult> execute(ExecutionPlan plan) {
        long start = System.currentTimeMillis();
        TaskDag dag = new TaskDag(plan);
        List<List<Task>> levels = dag.toLevels();
        List<TaskResult> allResults = new ArrayList<>();

        log.info("Orchestrator 开始执行: {} 任务, {} 层", dag.taskCount(), levels.size());

        for (int levelIdx = 0; levelIdx < levels.size(); levelIdx++) {
            final int currentLevelIdx = levelIdx;
            List<Task> currentLevel = levels.get(currentLevelIdx);
            log.info("执行第 {}/{} 层, {} 个并行任务: {}",
                    currentLevelIdx + 1, levels.size(), currentLevel.size(),
                    currentLevel.stream().map(Task::id).toList());

            // 同层内所有任务并行提交
            List<CompletableFuture<TaskResult>> futures = currentLevel.stream()
                    .map(task -> CompletableFuture.supplyAsync(() -> {
                        WorkerAgent worker = new WorkerAgent(chatModel, tools);
                        return worker.execute(task, tools);
                    }, executor))
                    .toList();

            // 等待当前层全部完成（最多 5 分钟）
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(5, TimeUnit.MINUTES)
                    .exceptionally(ex -> {
                        log.error("层级 {} 执行超时或失败: {}", currentLevelIdx + 1, ex.getMessage());
                        return null;
                    })
                    .join();

            List<TaskResult> levelResults = futures.stream()
                    .map(f -> {
                        try { return f.getNow(TaskResult.failed("unknown", "timeout", 0)); }
                        catch (Exception e) { return TaskResult.failed("unknown", e.getMessage(), 0); }
                    })
                    .toList();

            allResults.addAll(levelResults);
            log.info("第 {} 层完成: {}", currentLevelIdx + 1,
                    levelResults.stream().map(r -> r.taskId() + "=" + r.status()).toList());
        }

        long totalDuration = System.currentTimeMillis() - start;
        log.info("Orchestrator 完成: {} 任务, {}ms", allResults.size(), totalDuration);
        return allResults;
    }
}
