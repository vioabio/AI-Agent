package com.vio.vioaiagent.multiagent;

import com.vio.vioaiagent.multiagent.model.ExecutionPlan;
import com.vio.vioaiagent.multiagent.model.TaskResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 多智能体编排器 — Plan-and-Execute 模式的顶层入口.
 *
 * <p>组合 Planner → Orchestrator → Workers → Reviewer 全流程.
 * 提供同步和 SSE 流式两种执行模式.
 *
 * <pre>{@code
 * MultiAgentOrchestrator orchestrator = new MultiAgentOrchestrator(
 *     chatModel, tools, executor);
 * OrchestrationResult result = orchestrator.execute("搜索宝可梦，生成PDF报告");
 * }</pre>
 *
 * @author vio
 */
@Slf4j
public class MultiAgentOrchestrator {

    private final PlannerAgent planner;
    private final AgentOrchestrator orchestrator;
    private final ReviewerAgent reviewer;

    public MultiAgentOrchestrator(ChatModel chatModel, ToolCallback[] tools, Executor executor) {
        this.planner = new PlannerAgent(chatModel);
        this.orchestrator = new AgentOrchestrator(chatModel, tools, executor);
        this.reviewer = new ReviewerAgent(chatModel);
    }

    /**
     * 同步执行 — Plan → Execute → Review.
     *
     * @param userRequest 用户请求
     * @return 编排结果（含所有任务结果、评分、最终输出）
     */
    public OrchestrationResult execute(String userRequest) {
        long start = System.currentTimeMillis();

        // Phase 1: Plan
        log.info("===== MultiAgent Phase 1: Planner =====");
        ExecutionPlan plan = planner.plan(userRequest);

        // Phase 2: Execute
        log.info("===== MultiAgent Phase 2: Orchestrator =====");
        List<TaskResult> results = orchestrator.execute(plan);

        // Phase 3: Review
        log.info("===== MultiAgent Phase 3: Reviewer =====");
        ReviewVerdict verdict = reviewer.reviewWithRetry(
                plan.tasks(), results,
                (retryTasks, currentResults) -> {
                    ExecutionPlan retryPlan = new ExecutionPlan(retryTasks, userRequest);
                    return orchestrator.execute(retryPlan);
                });

        long totalDuration = System.currentTimeMillis() - start;

        // 聚合最终输出
        String finalOutput = buildFinalOutput(plan, results, verdict);
        int retries = verdict.passed() ? 0 : 1;

        log.info("===== MultiAgent 完成: {}ms, score={} =====", totalDuration, verdict.score());
        return new OrchestrationResult(results, totalDuration, verdict.score(), finalOutput, retries);
    }

    /**
     * SSE 流式执行 — 每步进度实时推送.
     */
    public SseEmitter executeStream(String userRequest) {
        SseEmitter emitter = new SseEmitter(600000L);

        CompletableFuture.runAsync(() -> {
            try {
                emitter.send("[PLAN] 正在分析任务...");
                ExecutionPlan plan = planner.plan(userRequest);
                emitter.send("[PLAN] 拆解为 " + plan.taskCount() + " 个子任务");

                emitter.send("[EXECUTE] 开始执行...");
                List<TaskResult> results = orchestrator.execute(plan);

                for (TaskResult r : results) {
                    emitter.send("[STEP] " + r.taskId() + " → " + r.status()
                            + " (" + r.durationMs() + "ms)");
                }

                emitter.send("[REVIEW] 质量审查中...");
                ReviewVerdict verdict = reviewer.review(plan.tasks(), results);
                emitter.send("[DONE] 评分: " + verdict.score() + "/100 — " + verdict.feedback());
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send("[ERROR] " + e.getMessage());
                } catch (IOException ignored) {}
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private String buildFinalOutput(ExecutionPlan plan, List<TaskResult> results, ReviewVerdict verdict) {
        StringBuilder sb = new StringBuilder();
        sb.append("===== 执行报告 =====\n");
        sb.append("任务数: ").append(plan.taskCount()).append("\n");
        sb.append("成功: ").append(results.stream()
                .filter(r -> r.status() == TaskResult.Status.SUCCESS).count()).append("\n");
        sb.append("质量评分: ").append(verdict.score()).append("/100\n");
        sb.append("反馈: ").append(verdict.feedback()).append("\n\n");

        sb.append("--- 各任务结果 ---\n");
        for (TaskResult r : results) {
            sb.append("[").append(r.status()).append("] ").append(r.taskId())
                    .append(" (").append(r.durationMs()).append("ms)\n");
            if (r.output() != null) sb.append("  ").append(r.output()).append("\n");
            if (r.errorMessage() != null) sb.append("  错误: ").append(r.errorMessage()).append("\n");
        }
        return sb.toString();
    }
}
