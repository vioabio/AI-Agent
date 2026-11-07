package com.vio.vioaiagent.security;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * PostgreSQL 原子配额适配器。它用唯一桶 + UPSERT 的条件更新保证并发下
 * 不超过上限；部署到多实例前仍建议切换到 Redis/Lua，并保留同一接口。
 */
@Repository
@Profile({"prod", "demo"})
@Primary
@ConditionalOnProperty(prefix = "vio.security", name = "global-quota-enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "vio.security", name = "global-quota-backend",
        havingValue = "jdbc", matchIfMissing = true)
public class JdbcGlobalQuotaStore implements GlobalQuotaStore {
    private final JdbcTemplate jdbc;

    public JdbcGlobalQuotaStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public QuotaDecision consume(String scope, String subject, int limit, Duration window, Instant now) {
        validate(scope, subject, limit, window);
        Instant point = now == null ? Instant.now() : now;
        long bucket = Math.floorDiv(point.toEpochMilli(), window.toMillis());
        String key = scope.trim() + ':' + subject.trim();
        Instant expires = point.plus(window);
        try {
            Integer used = jdbc.queryForObject("""
                    INSERT INTO global_quota_counter (scope_key, window_bucket, used, expires_at, updated_at)
                    VALUES (?, ?, 1, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT (scope_key, window_bucket) DO UPDATE
                    SET used = global_quota_counter.used + 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE global_quota_counter.used < ?
                    RETURNING used
                    """, Integer.class, key, bucket, Timestamp.from(expires), limit);
            return GlobalQuotaStore.QuotaDecision.allowed(used == null ? 1 : used, limit);
        } catch (EmptyResultDataAccessException denied) {
            try {
                Integer used = jdbc.queryForObject("""
                        SELECT used FROM global_quota_counter
                        WHERE scope_key = ? AND window_bucket = ?
                        """, Integer.class, key, bucket);
                return GlobalQuotaStore.QuotaDecision.denied(used == null ? limit : used, limit, expires);
            } catch (EmptyResultDataAccessException race) {
                // A concurrent purge can remove the bucket between the failed
                // UPSERT and read-back.  Do not grant a request on incomplete
                // accounting; fail closed and let the caller retry next window.
                return GlobalQuotaStore.QuotaDecision.denied(limit, limit, expires);
            }
        }
    }

    @Override
    public int purgeExpired(Instant now, int limit) {
        Instant point = now == null ? Instant.now() : now;
        int bounded = Math.max(1, Math.min(limit, 10_000));
        return jdbc.update("""
                DELETE FROM global_quota_counter WHERE ctid IN (
                    SELECT ctid FROM global_quota_counter WHERE expires_at <= ? LIMIT ?
                )
                """, Timestamp.from(point), bounded);
    }

    private static void validate(String scope, String subject, int limit, Duration window) {
        if (scope == null || scope.isBlank() || subject == null || subject.isBlank()
                || limit <= 0 || window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("全局配额参数无效");
        }
    }
}
