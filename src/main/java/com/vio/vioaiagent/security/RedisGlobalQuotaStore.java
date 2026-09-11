package com.vio.vioaiagent.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Redis/Lua 的跨实例窗口配额实现。
 *
 * <p>一次 {@code EVAL} 完成读取、限额判断、INCR 和首次 TTL 设置，避免
 * GET/INCR/EXPIRE 之间的竞态。Redis 不可用时故意抛出运行时异常，由
 * {@link TokenStore} 拒绝请求；绝不回退到 JVM 内存窗口。</p>
 *
 * <p>键格式为 {@code vio:quota:v1:<sha256(scope)>:<sha256(subject)>:<bucket>}。
 * scope/subject 不以明文写入 Redis，避免将租户、用户或令牌标识落入键名。</p>
 */
public final class RedisGlobalQuotaStore implements GlobalQuotaStore {

    static final String KEY_PREFIX = "vio:quota:v1:";

    /**
     * 返回 [allowed(0/1), used, pttlMillis]。
     *
     * <p>拒绝时不再递增计数，避免恶意流量把计数器无限放大；脚本仍在
     * Redis 单线程执行上下文中原子完成所有步骤。</p>
     */
    static final String QUOTA_LUA = """
            local key = KEYS[1]
            local limit = tonumber(ARGV[1])
            local windowMillis = tonumber(ARGV[2])
            local current = tonumber(redis.call('GET', key) or '0')
            local ttl = redis.call('PTTL', key)
            if ttl < 0 then
              ttl = windowMillis
              redis.call('PEXPIRE', key, windowMillis)
            end
            if current >= limit then
              if ttl < 1 then ttl = windowMillis end
              return {0, current, ttl}
            end
            current = redis.call('INCR', key)
            if current == 1 then
              redis.call('PEXPIRE', key, windowMillis)
              ttl = windowMillis
            else
              ttl = redis.call('PTTL', key)
              if ttl < 1 then ttl = windowMillis end
            end
            return {1, current, ttl}
            """;

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> QUOTA_SCRIPT =
            new DefaultRedisScript<>(QUOTA_LUA, List.class);

    private final RedisQuotaScriptExecutor executor;

    /** Spring production constructor; the template is auto-configured by Boot. */
    public RedisGlobalQuotaStore(StringRedisTemplate redisTemplate) {
        Objects.requireNonNull(redisTemplate, "redisTemplate");
        this.executor = (key, limit, ttlMillis) -> redisTemplate.execute(
                QUOTA_SCRIPT,
                List.of(key),
                Integer.toString(limit),
                Long.toString(ttlMillis));
    }

    /**
     * Package-private seam for deterministic unit tests. It does not create a
     * network client and must not be used by application wiring.
     */
    RedisGlobalQuotaStore(RedisQuotaScriptExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public QuotaDecision consume(String scope, String subject, int limit, Duration window, Instant now) {
        validate(scope, subject, limit, window);
        Instant point = now == null ? Instant.now() : now;
        long windowMillis;
        try {
            windowMillis = window.toMillis();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("全局配额窗口过大", ex);
        }
        if (windowMillis <= 0) throw new IllegalArgumentException("全局配额窗口过小");

        long bucket = Math.floorDiv(point.toEpochMilli(), windowMillis);
        String key = buildKey(scope, subject, bucket);
        List<?> result;
        try {
            result = executor.execute(key, limit, windowMillis);
        } catch (RuntimeException ex) {
            // Do not silently switch to local/JDBC accounting after a Redis
            // outage: that would let another instance bypass the global limit.
            if (ex instanceof QuotaStoreUnavailableException) throw ex;
            throw new QuotaStoreUnavailableException("Redis 全局配额存储不可用", ex);
        }
        if (result == null || result.size() < 3) {
            throw new QuotaStoreUnavailableException("Redis 配额脚本返回值无效");
        }
        long allowed = parseReply(result.get(0));
        long used = parseReply(result.get(1));
        long ttlMillis = parseReply(result.get(2));
        if ((allowed != 0 && allowed != 1) || used < 0 || used > Integer.MAX_VALUE
                || ttlMillis < 0) {
            throw new QuotaStoreUnavailableException("Redis 配额脚本返回值越界");
        }
        int boundedUsed = (int) used;
        if (allowed == 1) return QuotaDecision.allowed(boundedUsed, limit);
        Instant retryAt = point.plusMillis(Math.max(1L, ttlMillis));
        return QuotaDecision.denied(boundedUsed, limit, retryAt);
    }

    /** Redis key 具有 TTL，不需要额外扫描/删除任务。 */
    @Override
    public int purgeExpired(Instant now, int limit) {
        return 0;
    }

    static String buildKey(String scope, String subject, long bucket) {
        return KEY_PREFIX + sha256(scope.trim()) + ':' + sha256(subject.trim()) + ':' + bucket;
    }

    private static long parseReply(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof byte[] bytes) return Long.parseLong(new String(bytes, StandardCharsets.UTF_8));
        if (value instanceof CharSequence text) return Long.parseLong(text.toString());
        throw new QuotaStoreUnavailableException("Redis 配额脚本返回类型无效");
    }

    private static void validate(String scope, String subject, int limit, Duration window) {
        if (scope == null || scope.isBlank() || subject == null || subject.isBlank()
                || scope.trim().length() > 128 || subject.trim().length() > 512
                || limit <= 0 || window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("全局配额参数无效");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JRE 不支持 SHA-256", ex);
        }
    }

    @FunctionalInterface
    interface RedisQuotaScriptExecutor {
        List<?> execute(String key, int limit, long ttlMillis);
    }

    /** Marker exception used by callers to distinguish infrastructure denial. */
    public static final class QuotaStoreUnavailableException extends RuntimeException {
        public QuotaStoreUnavailableException(String message) { super(message); }
        public QuotaStoreUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
