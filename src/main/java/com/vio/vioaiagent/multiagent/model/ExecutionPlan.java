package com.vio.vioaiagent.multiagent.model;

import java.util.List;

/**
 * 执行计划 — Planner 的输出.
 *
 * @param tasks       子任务列表
 * @param userRequest 原始用户请求（透传, 便于日志和调试）
 * @author vio
 */
public record ExecutionPlan(List<Task> tasks, String userRequest) {

    public int taskCount() { return tasks != null ? tasks.size() : 0; }
}
