package com.vio.vioaiagent.security;

import java.time.Instant;
import java.util.Map;

/**
 * 审计条目 — 记录一次工具调用的完整上下文.
 *
 * <p>安全体系第四层：审计追溯.
 * 每条记录包含操作者、操作内容、审批结果和执行耗时，支持全链路回溯.
 *
 * @param traceId    链路追踪 ID
 * @param sessionId  会话 ID
 * @param userId     用户 ID
 * @param toolName   工具名称
 * @param params     参数（已脱敏）
 * @param outcome    结果：allow / deny / error
 * @param approver   审批来源：hitl / policy / none
 * @param durationMs 执行耗时（毫秒）
 * @param timestamp  时间戳
 * @author vio
 */
public record AuditEntry(
        String traceId,
        String sessionId,
        String userId,
        String toolName,
        Map<String, Object> params,
        String outcome,
        String approver,
        long durationMs,
        Instant timestamp) {

    /** 创建审计条目（自动填充时间戳） */
    public static AuditEntry of(String traceId, String sessionId, String userId,
                                 String toolName, Map<String, Object> params,
                                 String outcome, String approver, long durationMs) {
        return new AuditEntry(traceId, sessionId, userId, toolName,
                params, outcome, approver, durationMs, Instant.now());
    }
}
