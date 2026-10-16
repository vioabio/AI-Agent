package com.vio.vioaiagent.security;

import java.time.Instant;

/** Separate security fact: revocation never mutates the immutable snapshot. */
public record AuthorizationRevocation(String runId, Instant revokedAt, String reason) {
    public AuthorizationRevocation {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId 不能为空");
        revokedAt = revokedAt == null ? Instant.now() : revokedAt;
        reason = reason == null || reason.isBlank() ? "REVOKED" : reason.trim();
        if (reason.length() > 256) throw new IllegalArgumentException("撤销原因过长");
    }
}
