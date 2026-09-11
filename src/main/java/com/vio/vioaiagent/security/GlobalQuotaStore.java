package com.vio.vioaiagent.security;

import java.time.Duration;
import java.time.Instant;

/**
 * 跨实例配额存储边界。生产实现应使用 Redis/Lua 或等价原子存储；
 * JDBC 实现仅作为可审计的过渡适配器，不改变调用方契约。
 */
public interface GlobalQuotaStore {
    QuotaDecision consume(String scope, String subject, int limit, Duration window, Instant now);

    default int purgeExpired(Instant now, int limit) { return 0; }

    record QuotaDecision(boolean allowed, int used, int limit, Instant retryAt) {
        public QuotaDecision {
            if (used < 0 || limit < 0) throw new IllegalArgumentException("配额计数无效");
        }

        public static QuotaDecision allowed(int used, int limit) {
            return new QuotaDecision(true, used, limit, null);
        }

        public static QuotaDecision denied(int used, int limit, Instant retryAt) {
            return new QuotaDecision(false, used, limit, retryAt);
        }

        public long retryAfterSeconds(Instant now) {
            if (retryAt == null) return 0;
            Instant point = now == null ? Instant.now() : now;
            return Math.max(1, Duration.between(point, retryAt).toSeconds());
        }
    }
}
