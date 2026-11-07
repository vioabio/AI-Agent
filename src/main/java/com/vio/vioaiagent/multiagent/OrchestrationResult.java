package com.vio.vioaiagent.multiagent;

import com.vio.vioaiagent.multiagent.model.TaskResult;

import java.util.List;

/**
 * 编排结果 — 多智能体执行后的最终输出.
 *
 * @param taskResults      所有子任务的结果
 * @param totalDurationMs  总耗时
 * @param reviewScore      审查评分 (0-100)
 * @param finalOutput      聚合后的最终输出文本
 * @param retriesTriggered 触发的重试次数
 * @author vio
 */
public record OrchestrationResult(
        List<TaskResult> taskResults,
        long totalDurationMs,
        int reviewScore,
        String finalOutput,
        int retriesTriggered) {

    /** 是否所有任务都成功 */
    public boolean isAllSuccess() {
        return taskResults.stream()
                .allMatch(r -> r.status() == com.vio.vioaiagent.multiagent.model.TaskResult.Status.SUCCESS);
    }
}
