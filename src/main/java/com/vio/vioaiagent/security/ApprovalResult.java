package com.vio.vioaiagent.security;

/**
 * HITL 审批结果.
 *
 * @param action    审批动作
 * @param sessionId 关联的会话 ID
 * @param reason    审批原因（拒绝时说明原因）
 * @author vio
 */
public record ApprovalResult(ApprovalAction action, String sessionId, String reason) {

    /**
     * 审批动作.
     */
    public enum ApprovalAction {
        /** 批准本次操作 */
        APPROVE,
        /** 全部放行（会话内后续操作不再审批） */
        APPROVE_ALL,
        /** 拒绝本次操作 */
        DENY,
        /** 跳过（不执行，不拒绝） */
        SKIP,
        /** 修改参数后执行 */
        MODIFY
    }

    /** 创建批准结果 */
    public static ApprovalResult approve(String sessionId) {
        return new ApprovalResult(ApprovalAction.APPROVE, sessionId, null);
    }

    /** 创建全部放行结果 */
    public static ApprovalResult approveAll(String sessionId) {
        return new ApprovalResult(ApprovalAction.APPROVE_ALL, sessionId, null);
    }

    /** 创建拒绝结果 */
    public static ApprovalResult deny(String sessionId, String reason) {
        return new ApprovalResult(ApprovalAction.DENY, sessionId, reason);
    }
}
