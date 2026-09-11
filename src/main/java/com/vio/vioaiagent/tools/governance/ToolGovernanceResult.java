package com.vio.vioaiagent.tools.governance;

/** 工具治理网关的结构化结果；回调层可用 {@link #toModelText()} 兼容旧 Agent。 */
public record ToolGovernanceResult(
        Status status,
        String toolName,
        String output,
        String approvalId,
        String eventId,
        int retryCount,
        long durationMs) {

    public enum Status {
        EXECUTED,
        DEDUPLICATED,
        WAITING_APPROVAL,
        REJECTED,
        FAILED
    }

    public String toModelText() {
        return switch (status) {
            case EXECUTED, DEDUPLICATED -> output == null ? "" : output;
            case WAITING_APPROVAL -> "[approval.required] 工具 " + toolName
                    + " 等待人工审批，approvalId=" + approvalId;
            case REJECTED -> "[tool.denied] 工具 " + toolName + " 已拒绝：" + safe(output);
            case FAILED -> "[tool.error] 工具 " + toolName + " 执行失败：" + safe(output);
        };
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "未知原因" : value;
    }
}
