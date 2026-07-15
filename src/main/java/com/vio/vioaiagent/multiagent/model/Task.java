package com.vio.vioaiagent.multiagent.model;

import java.util.Collections;
import java.util.List;

/**
 * 任务节点 — 执行计划中的一个子任务.
 *
 * @param id             唯一标识（LLM 分配, 如 "task-1"）
 * @param description    任务描述
 * @param dependsOn      前置任务 ID 列表（空列表表示无依赖, 可并行）
 * @param suggestedTool  建议使用的工具名（可为 null）
 * @param expectedOutput 预期输出描述（供 Reviewer 对比）
 * @author vio
 */
public record Task(
        String id,
        String description,
        List<String> dependsOn,
        String suggestedTool,
        String expectedOutput) {

    public Task {
        if (dependsOn == null) dependsOn = Collections.emptyList();
    }

    public static Task of(String id, String description) {
        return new Task(id, description, Collections.emptyList(), null, null);
    }

    public static Task of(String id, String description, List<String> dependsOn) {
        return new Task(id, description, dependsOn, null, null);
    }
}
