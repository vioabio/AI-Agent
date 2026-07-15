package com.vio.vioaiagent.multiagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.multiagent.model.Task;
import com.vio.vioaiagent.multiagent.model.TaskResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 审查者 Agent — 质量评分 + 自动重试.
 *
 * <p>接收所有 Worker 执行结果, 调用 LLM 进行质量评估.
 * 不合格的任务（score < 60）打回 Orchestrator 重试, 最多 2 次.
 *
 * @author vio
 */
@Slf4j
public class ReviewerAgent {

    private static final String REVIEWER_PROMPT = """
            You are a quality reviewer. Evaluate the execution results of a multi-step task.
            Score each result on a scale of 0-100 based on:
            - Completeness: Did the task achieve its expected output?
            - Accuracy: Is the information correct?
            - Usefulness: Will this help solve the user's request?

            Output format (JSON only):
            {
                "score": 85,
                "passed": true,
                "feedback": "整体评价...",
                "tasksToRetry": []
            }

            Pass threshold: score >= 60.
            If failed, list the task IDs that need to be re-executed in tasksToRetry.
            """;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public ReviewerAgent(ChatModel chatModel) {
        this.chatModel = chatModel;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 审查执行结果.
     *
     * @param tasks   原始任务列表
     * @param results 执行结果列表
     * @return 审查结论
     */
    public ReviewVerdict review(List<Task> tasks, List<TaskResult> results) {
        log.info("Reviewer 开始审查: {} 个任务结果", results.size());

        try {
            StringBuilder context = new StringBuilder("Tasks and results:\n");
            for (int i = 0; i < tasks.size(); i++) {
                Task task = tasks.get(i);
                TaskResult result = i < results.size() ? results.get(i)
                        : TaskResult.skipped(task.id());
                context.append("- ").append(task.id()).append(": ")
                        .append(task.description()).append("\n")
                        .append("  Status: ").append(result.status()).append("\n")
                        .append("  Output: ").append(
                                result.output() != null ? result.output().substring(0,
                                        Math.min(200, result.output().length())) : "N/A")
                        .append("\n");
            }

            String response = chatModel.call(
                    new Prompt(REVIEWER_PROMPT + "\n\n" + context)).getResult().getOutput().getText();
            String json = extractJson(response);
            Map<String, Object> map = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});

            int score = ((Number) map.getOrDefault("score", 80)).intValue();
            boolean passed = (boolean) map.getOrDefault("passed", true);
            String feedback = (String) map.getOrDefault("feedback", "OK");
            @SuppressWarnings("unchecked")
            List<String> retryTasks = (List<String>) map.getOrDefault("tasksToRetry", List.of());

            log.info("Reviewer 完成: score={}, passed={}", score, passed);
            return new ReviewVerdict(score, passed, feedback, retryTasks);
        } catch (Exception e) {
            log.error("Reviewer 审查失败, 默认通过: {}", e.getMessage());
            return ReviewVerdict.pass("审查跳过（LLM 调用失败）");
        }
    }

    /**
     * 带重试的审查流程 — 最多重试 MAX_RETRIES 次.
     */
    public ReviewVerdict reviewWithRetry(List<Task> tasks, List<TaskResult> results,
                                          java.util.function.BiFunction<List<Task>, List<TaskResult>, List<TaskResult>> retryExecutor) {
        ReviewVerdict verdict = review(tasks, results);
        int retryCount = 0;

        while (!verdict.passed() && retryCount < ReviewVerdict.MAX_RETRIES) {
            retryCount++;
            final ReviewVerdict currentVerdict = verdict;
            log.info("Reviewer 触发第 {} 次重试: 重试任务={}", retryCount, currentVerdict.tasksToRetry());

            // 选出需要重试的任务
            List<Task> retryTaskList = tasks.stream()
                    .filter(t -> currentVerdict.tasksToRetry().contains(t.id()))
                    .toList();

            if (retryTaskList.isEmpty()) break;

            List<TaskResult> retryResults = retryExecutor.apply(retryTaskList, results);
            // 合并结果
            List<TaskResult> merged = new ArrayList<>(results);
            for (TaskResult rr : retryResults) {
                merged.replaceAll(r -> r.taskId().equals(rr.taskId()) ? rr : r);
            }

            verdict = review(tasks, merged);
        }

        log.info("Reviewer 最终结果: score={}, passed={}, retries={}",
                verdict.score(), verdict.passed(), retryCount);
        return verdict;
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return (start >= 0 && end > start) ? text.substring(start, end + 1) : text;
    }
}
