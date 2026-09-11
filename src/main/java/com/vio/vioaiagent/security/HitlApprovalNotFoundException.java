package com.vio.vioaiagent.security;

/** 审批不存在时的稳定异常类型；对外层可映射为 404/410。 */
public class HitlApprovalNotFoundException extends IllegalArgumentException {
    public HitlApprovalNotFoundException(String message) {
        super(message);
    }
}
