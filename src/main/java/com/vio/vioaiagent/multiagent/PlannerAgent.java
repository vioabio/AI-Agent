package com.vio.vioaiagent.multiagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.multiagent.model.ExecutionPlan;
import com.vio.vioaiagent.multiagent.model.Task;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

/**
 * 规划者 Agent — LLM 驱动的任务分解.
 *
 * <p>接收用户请求, 调用 LLM 分解为子任务列表并构建 DAG 依赖图.
 * LLM 的输出必须是严格的 JSON 格式, 包含每个任务的 id/description/dependsOn.
 *
 * @author vio
 */
@Slf4j
public class PlannerAgent {

    private static final String PLANNER_PROMPT = """
            You are a task planner. Given a user request, break it down into
            independent subtasks and identify dependencies between them.

            Output format (JSON only, no markdown, no explanation):
            {
                "tasks": [
                    {
                        "id": "task-1",
                        "description": "具体的任务描述",
                        "dependsOn": [],
                        "suggestedTool": "建议使用的工具名或null",
                        "expectedOutput": "预期输出描述"
                    }
                ]
            }

            Rules:
            1. id 使用 "task-1", "task-2", ... 格式
            2. dependsOn 列出所有前置任务的 id, 无依赖写 []
            3. 独立任务放在 dependsOn=[] 以便并行执行
            4. 有依赖的任务必须等待前置任务完成
            5. 建议的工具从以下选择: web_search, web_scraping, read_file, write_file,
               generatePDF, download_resource, terminal_operation, terminate
            """;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public PlannerAgent(ChatModel chatModel) {
        this.chatModel = chatModel;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 分解用户请求为执行计划.
     *
     * @param userRequest 用户原始请求
     * @return 执行计划（含任务列表和 DAG 依赖关系）
     */
    public ExecutionPlan plan(String userRequest) {
        log.info("Planner 开始拆解任务: {}", userRequest);

        try {
            ChatResponse response = chatModel.call(
                    new Prompt(PLANNER_PROMPT + "\n\nUser request: " + userRequest));
            String json = response.getResult().getOutput().getText();
            json = extractJson(json);

            Map<String, Object> planMap = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> taskList = (List<Map<String, Object>>) planMap.get("tasks");

            List<Task> tasks = taskList.stream().map(t -> {
                @SuppressWarnings("unchecked")
                List<String> deps = (List<String>) t.getOrDefault("dependsOn", List.of());
                return new Task(
                        (String) t.get("id"),
                        (String) t.get("description"),
                        deps,
                        (String) t.getOrDefault("suggestedTool", null),
                        (String) t.getOrDefault("expectedOutput", null)
                );
            }).toList();

            log.info("Planner 拆解完成: {} 个子任务", tasks.size());
            return new ExecutionPlan(tasks, userRequest);
        } catch (Exception e) {
            log.error("Planner 任务拆解失败: {}", e.getMessage(), e);
            // 降级: 返回单任务计划
            Task fallback = Task.of("task-1", userRequest);
            return new ExecutionPlan(List.of(fallback), userRequest);
        }
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }
}
