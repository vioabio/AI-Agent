package com.vio.vioaiagent.run;

import java.time.Instant;

/** Run 单 owner 租约；leaseToken 用于拒绝旧 worker 的迟到写入。 */
public record RunLease(String runId, String owner, String token, Instant expiresAt,
                       Instant heartbeatAt, long version) {
    public RunLease {
        require(runId, "runId");
        require(owner, "owner");
        require(token, "token");
        if (expiresAt == null) throw new IllegalArgumentException("expiresAt 不能为空");
        heartbeatAt = heartbeatAt == null ? Instant.now() : heartbeatAt;
        if (version < 0) throw new IllegalArgumentException("lease version 不能为负数");
    }

    public boolean activeAt(Instant now) {
        return expiresAt.isAfter(now == null ? Instant.now() : now);
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
    }
}
