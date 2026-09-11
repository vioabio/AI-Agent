package com.vio.vioaiagent.security;

import java.time.Instant;

/**
 * 脱敏后的 HITL 审计事实。只携带参数摘要，不保存原始工具参数或凭据，
 * 可由日志、数据库或消息总线适配器持久化。
 */
public record HitlAuditEvent(
        String eventId,
        String approvalId,
        String action,
        String userId,
        String runId,
        String toolName,
        String toolVersion,
        String parameterHash,
        String reason,
        Instant occurredAt) {

    public HitlAuditEvent {
        requireText(eventId, "eventId");
        requireText(action, "action");
        requireText(userId, "userId");
        requireText(runId, "runId");
        requireText(toolName, "toolName");
        requireBounded(eventId, 128, "eventId");
        requireBounded(action, 64, "action");
        requireBounded(userId, 128, "userId");
        requireBounded(runId, 128, "runId");
        requireBounded(toolName, 128, "toolName");
        if (approvalId != null) requireBounded(approvalId, 128, "approvalId");
        if (toolVersion != null) requireBounded(toolVersion, 128, "toolVersion");
        if (parameterHash != null) requireBounded(parameterHash, 256, "parameterHash");
        reason = sanitizeReason(reason);
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }

    private static void requireBounded(String value, int max, String field) {
        if (value.length() > max || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(field + " 长度或字符不合法");
        }
    }

    /**
     * Normalize free-form approval reasons before they cross an audit/event
     * boundary.  Run lifecycle events use the same policy as the HITL audit
     * record so a request cannot inject log lines or persist an obvious
     * credential value through the approval endpoint.
     */
    public static String sanitizeReason(String value) {
        if (value == null || value.isBlank()) return value;
        String safe = value.replaceAll("[\\r\\n\\t]", " ")
                .replaceAll("(?i)(password|passwd|secret|token|api[-_ ]?key|authorization)\\s*[:=]\\s*[^\\s,;]+",
                        "$1=[REDACTED]");
        return safe.length() > 2000 ? safe.substring(0, 2000) : safe;
    }
}
