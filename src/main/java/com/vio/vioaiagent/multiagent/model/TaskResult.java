package com.vio.vioaiagent.multiagent.model;

/**
 * 任务结果 — Worker 执行单个任务后的输出.
 *
 * @param taskId       对应的任务 ID
 * @param status       执行状态: SUCCESS / FAILED / SKIPPED / TIMED_OUT
 * @param output       成功时的输出文本
 * @param errorMessage 失败时的错误描述
 * @param durationMs   执行耗时（毫秒）
 * @author vio
 */
public record TaskResult(
        String taskId,
        Status status,
        String output,
        String errorMessage,
        long durationMs) {

    public enum Status { SUCCESS, FAILED, SKIPPED, TIMED_OUT }

    public static TaskResult success(String taskId, String output, long durationMs) {
        return new TaskResult(taskId, Status.SUCCESS, output, null, durationMs);
    }

    public static TaskResult failed(String taskId, String error, long durationMs) {
        return new TaskResult(taskId, Status.FAILED, null, error, durationMs);
    }

    public static TaskResult skipped(String taskId) {
        return new TaskResult(taskId, Status.SKIPPED, null, null, 0);
    }
}
