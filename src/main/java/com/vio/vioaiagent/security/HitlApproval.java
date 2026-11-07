package com.vio.vioaiagent.security;

import java.time.Instant;

/**
 * HITL 审批的持久化事实。与 {@link HitlManager.PendingApproval} 的短期运行时
 * 视图分离，保证重启后仍能恢复 user/run/tool 绑定、风险等级和最终决定。
 */
public record HitlApproval(
        String approvalId,
        String userId,
        String runId,
        String toolName,
        String toolVersion,
        String parameterDigest,
        String riskLevel,
        Instant expiresAt,
        Decision decision,
        String decidedBy,
        Instant decidedAt,
        String decisionReason,
        String leaseOwner,
        Instant leaseExpiresAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public HitlApproval {
        requireBounded(approvalId, 128, "approvalId");
        requireBounded(userId, 128, "userId");
        requireBounded(runId, 128, "runId");
        requireBounded(toolName, 128, "toolName");
        requireBounded(toolVersion, 128, "toolVersion");
        requireBounded(parameterDigest, 256, "parameterDigest");
        requireBounded(riskLevel, 32, "riskLevel");
        if (expiresAt == null) throw new IllegalArgumentException("expiresAt 不能为空");
        decision = decision == null ? Decision.PENDING : decision;
        if (decidedBy != null) requireBounded(decidedBy, 128, "decidedBy");
        if (decisionReason != null && (decisionReason.length() > 2000
                || decisionReason.indexOf('\u0000') >= 0)) {
            throw new IllegalArgumentException("decisionReason 长度或字符不合法");
        }
        if (leaseOwner != null) requireBounded(leaseOwner, 128, "leaseOwner");
        if (version < 0) throw new IllegalArgumentException("version 不能为负数");
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
        if (decision == Decision.PENDING) {
            if (decidedBy != null || decidedAt != null) {
                throw new IllegalArgumentException("PENDING 审批不应有 decided 字段");
            }
            if ((leaseOwner == null) != (leaseExpiresAt == null)) {
                throw new IllegalArgumentException("审批 lease 字段必须成组存在");
            }
        } else {
            if (decidedBy == null || decidedBy.isBlank() || decidedAt == null) {
                throw new IllegalArgumentException("终态审批必须有 decidedBy/decidedAt");
            }
            if (leaseOwner != null || leaseExpiresAt != null) {
                throw new IllegalArgumentException("终态审批不应保留 lease");
            }
        }
    }

    public boolean terminal() {
        return decision != Decision.PENDING;
    }

    public enum Decision { PENDING, APPROVED, DENIED, EXPIRED }

    private static void requireBounded(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max
                || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(name + " 长度或字符不合法");
        }
    }
}
