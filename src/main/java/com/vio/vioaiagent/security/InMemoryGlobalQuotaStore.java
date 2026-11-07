package com.vio.vioaiagent.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 单 JVM 测试/本地替身；生产跨实例部署不能依赖它。 */
public class InMemoryGlobalQuotaStore implements GlobalQuotaStore {
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    @Override
    public synchronized QuotaDecision consume(String scope, String subject, int limit,
                                               Duration window, Instant now) {
        validate(scope, subject, limit, window);
        Instant point = now == null ? Instant.now() : now;
        long bucket = Math.floorDiv(point.toEpochMilli(), window.toMillis());
        String key = scope.trim() + ':' + subject.trim() + ':' + bucket;
        Counter counter = counters.computeIfAbsent(key, ignored -> new Counter(point.plus(window)));
        if (!counter.expiresAt.isAfter(point)) {
            counter = new Counter(point.plus(window));
            counters.put(key, counter);
        }
        if (counter.used >= limit) return GlobalQuotaStore.QuotaDecision.denied(
                counter.used, limit, counter.expiresAt);
        counter.used++;
        return GlobalQuotaStore.QuotaDecision.allowed(counter.used, limit);
    }

    @Override
    public synchronized int purgeExpired(Instant now, int limit) {
        Instant point = now == null ? Instant.now() : now;
        int max = Math.max(1, limit);
        int deleted = 0;
        for (var entry : counters.entrySet()) {
            if (deleted >= max) break;
            if (!entry.getValue().expiresAt.isAfter(point) && counters.remove(entry.getKey(), entry.getValue())) deleted++;
        }
        return deleted;
    }

    private static void validate(String scope, String subject, int limit, Duration window) {
        if (scope == null || scope.isBlank() || subject == null || subject.isBlank()
                || limit <= 0 || window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("全局配额参数无效");
        }
    }

    private static final class Counter {
        private int used;
        private final Instant expiresAt;
        private Counter(Instant expiresAt) { this.expiresAt = expiresAt; }
    }
}
