package com.vio.vioaiagent.security;

/** OIDC principal policy 解析/授权结果；拒绝原因不得包含凭据或 JWT 内容。 */
public record PrincipalPolicyDecision(
        boolean applicable,
        boolean allowed,
        String reason,
        PrincipalPolicy policy) {

    public PrincipalPolicyDecision {
        reason = reason == null || reason.isBlank() ? (allowed ? "" : "策略拒绝") : reason;
        if (!allowed && policy != null) {
            throw new IllegalArgumentException("拒绝结果不能携带已授权策略");
        }
        if (allowed && applicable && policy == null) {
            throw new IllegalArgumentException("适用的允许结果必须携带策略");
        }
    }

    public static PrincipalPolicyDecision notApplicable() {
        return new PrincipalPolicyDecision(false, true, "OIDC policy 未启用", null);
    }

    public static PrincipalPolicyDecision allow(PrincipalPolicy policy) {
        return new PrincipalPolicyDecision(true, true, "", policy);
    }

    public static PrincipalPolicyDecision deny(String reason) {
        return new PrincipalPolicyDecision(true, false, reason, null);
    }
}
