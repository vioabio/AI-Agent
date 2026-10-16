package com.vio.vioaiagent.security;

import java.time.Instant;
import java.util.Set;

/** Durable, secret-free authorization facts captured when a Run is admitted. */
public record AuthorizationSnapshot(
        String runId,
        String principalId,
        String tenantId,
        Set<String> roles,
        Set<String> allowedTools,
        String policyVersion,
        String policyFingerprint,
        String bindingId,
        Instant issuedAt,
        Instant expiresAt) {
    public AuthorizationSnapshot {
        required(runId, "runId");
        required(principalId, "principalId");
        required(tenantId, "tenantId");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
        required(policyVersion, "policyVersion");
        required(policyFingerprint, "policyFingerprint");
        bindingId = bindingId == null ? "" : bindingId.trim();
        issuedAt = issuedAt == null ? Instant.now() : issuedAt;
        if (expiresAt != null && !expiresAt.isAfter(issuedAt)) throw new IllegalArgumentException("expiresAt 必须晚于 issuedAt");
    }

    public static AuthorizationSnapshot from(String runId, PrincipalPolicy policy, Instant issuedAt) {
        if (policy == null) throw new IllegalArgumentException("policy 不能为空");
        return new AuthorizationSnapshot(runId, policy.principalId(), policy.tenantId(), policy.roles(),
                policy.allowedTools(), policy.policyVersion(), policy.fingerprint(),
                policy.tokenBinding() == null ? "" : policy.tokenBinding().tokenId(), issuedAt, policy.expiresAt());
    }

    public boolean expired(Instant now) { return expiresAt != null && (now == null || !now.isBefore(expiresAt)); }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
    }
}
