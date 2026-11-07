package com.vio.vioaiagent.run;

import java.time.Instant;
import java.util.Map;

/** 通用 HTTP 幂等记录；responseBody 只保存脱敏的稳定响应摘要。 */
public record IdempotencyRecord(String scope, String key, String requestHash, int responseStatus,
                                Map<String, Object> responseBody, Instant createdAt, Instant expiresAt) {
    public IdempotencyRecord {
        require(scope, "scope"); require(key, "key"); require(requestHash, "requestHash");
        if (responseStatus < 100 || responseStatus > 599) throw new IllegalArgumentException("responseStatus 无效");
        responseBody = responseBody == null ? Map.of() : Map.copyOf(responseBody);
        createdAt = createdAt == null ? Instant.now() : createdAt;
        if (expiresAt == null || !expiresAt.isAfter(createdAt)) throw new IllegalArgumentException("expiresAt 无效");
    }
    public boolean validAt(Instant now) { return expiresAt.isAfter(now == null ? Instant.now() : now); }
    private static void require(String value, String field) { if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空"); }
}
