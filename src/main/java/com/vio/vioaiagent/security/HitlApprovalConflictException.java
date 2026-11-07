package com.vio.vioaiagent.security;

/** 审批状态或主体绑定发生并发冲突时的稳定异常类型。 */
public class HitlApprovalConflictException extends IllegalStateException {
    public HitlApprovalConflictException(String message) {
        super(message);
    }
}
