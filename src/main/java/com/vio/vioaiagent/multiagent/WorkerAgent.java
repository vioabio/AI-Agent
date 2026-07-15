package com.vio.vioaiagent.multiagent;

import com.vio.vioaiagent.multiagent.model.Task;
import com.vio.vioaiagent.multiagent.model.TaskResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

/**
 * 执行者 Agent — 执行单个子任务.
 *
 * <p>每个 Worker 实例处理一个 Task, 共享全局工具集.
 * Worker 是轻量级的, 不继承 BaseAgent, 直接使用 ChatClient 单轮调用工具.
 *
 * @author vio
 */
@Slf4j
public class WorkerAgent {

    private static final String WORKER_PROMPT = """
            You are a task execution specialist. Execute the given task using available tools.
            Focus only on this specific task — do not try to solve the entire problem.
            After completing the task, call the terminate tool to signal completion.
            """;

    private final ChatClient chatClient;

    /**
     * @param chatModel 共享的 ChatModel
     * @param tools     共享的工具集
     */
    public WorkerAgent(ChatModel chatModel, ToolCallback[] tools) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(WORKER_PROMPT)
                .build();
        // tools 在每次调用时传入
    }

    /**
     * 执行单个任务.
     *
     * @param task  任务定义
     * @param tools 可用工具集
     * @return 任务执行结果
     */
    public TaskResult execute(Task task, ToolCallback[] tools) {
        long start = System.currentTimeMillis();
        log.info("Worker 开始执行: {} — {}", task.id(), task.description());

        try {
            String userMessage = "Task: " + task.description() + "\n"
                    + (task.expectedOutput() != null
                    ? "Expected output: " + task.expectedOutput() : "");

            String result = chatClient.prompt()
                    .user(userMessage)
                    .toolCallbacks(tools)
                    .call()
                    .content();

            long duration = System.currentTimeMillis() - start;
            log.info("Worker 完成: {} ({}ms)", task.id(), duration);
            return TaskResult.success(task.id(), result, duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("Worker 失败: {} — {}", task.id(), e.getMessage());
            return TaskResult.failed(task.id(), e.getMessage(), duration);
        }
    }
}
