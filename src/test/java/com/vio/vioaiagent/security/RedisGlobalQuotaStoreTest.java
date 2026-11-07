package com.vio.vioaiagent.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RedisGlobalQuotaStoreTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void scriptKeepsIncrementAndExpiryAtomicAndDoesNotIncrementDeniedRequests() {
        assertTrue(RedisGlobalQuotaStore.QUOTA_LUA.contains("redis.call('INCR', key)"));
        assertTrue(RedisGlobalQuotaStore.QUOTA_LUA.contains("redis.call('PEXPIRE', key, windowMillis)"));
        assertTrue(RedisGlobalQuotaStore.QUOTA_LUA.contains("if current >= limit then"));
        assertFalse(RedisGlobalQuotaStore.QUOTA_LUA.contains("redis.call('INCR', key)\nif current >= limit"));
    }

    @Test
    void consumeUsesBoundedTtlAndHashedKeyWithoutLeakingSubject() {
        AtomicReference<String> key = new AtomicReference<>();
        AtomicReference<Long> ttl = new AtomicReference<>();
        AtomicReference<Integer> limit = new AtomicReference<>();
        RedisGlobalQuotaStore store = new RedisGlobalQuotaStore((capturedKey, capturedLimit, capturedTtl) -> {
            key.set(capturedKey);
            limit.set(capturedLimit);
            ttl.set(capturedTtl);
            return List.of(1L, 1L, 60_000L);
        });

        var decision = store.consume("token-minute", "secret-token-value", 3,
                Duration.ofMinutes(1), NOW);

        assertTrue(decision.allowed());
        assertEquals(1, decision.used());
        assertEquals(3, limit.get());
        assertEquals(60_000L, ttl.get());
        long bucket = Math.floorDiv(NOW.toEpochMilli(), Duration.ofMinutes(1).toMillis());
        assertEquals(RedisGlobalQuotaStore.buildKey("token-minute", "secret-token-value", bucket), key.get());
        assertTrue(key.get().startsWith(RedisGlobalQuotaStore.KEY_PREFIX));
        assertFalse(key.get().contains("secret-token-value"));
        assertFalse(key.get().contains("token-minute"));
    }

    @Test
    void deniedReplyCarriesRedisTtlAsRetryAt() {
        RedisGlobalQuotaStore store = new RedisGlobalQuotaStore(
                (key, limit, ttl) -> List.of(0L, 2L, 1_500L));

        var decision = store.consume("scope", "subject", 2,
                Duration.ofMinutes(1), NOW);

        assertFalse(decision.allowed());
        assertEquals(2, decision.used());
        assertEquals(2, decision.limit());
        assertEquals(1, decision.retryAfterSeconds(NOW));
    }

    @Test
    void redisFailureIsFailClosedAndNeverFallsBackToJvmWindow() {
        RedisGlobalQuotaStore store = new RedisGlobalQuotaStore((key, limit, ttl) -> {
            throw new IllegalStateException("connection refused");
        });

        var failure = assertThrows(RedisGlobalQuotaStore.QuotaStoreUnavailableException.class,
                () -> store.consume("token-minute", "digest-only", 1,
                        Duration.ofMinutes(1), NOW));
        assertFalse(failure.getMessage().contains("digest-only"));
        assertFalse(failure.getMessage().contains("connection refused"));
    }

    @Test
    void malformedScriptReplyIsFailClosed() {
        RedisGlobalQuotaStore store = new RedisGlobalQuotaStore(
                (key, limit, ttl) -> List.of(1L, 1L));
        assertThrows(RedisGlobalQuotaStore.QuotaStoreUnavailableException.class,
                () -> store.consume("scope", "subject", 1, Duration.ofMinutes(1), NOW));
    }

    @Test
    void invalidInputIsRejectedBeforeScriptExecution() {
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        RedisGlobalQuotaStore store = new RedisGlobalQuotaStore((key, limit, ttl) -> {
            called.set(true);
            return List.of(1L, 1L, 1L);
        });
        assertThrows(IllegalArgumentException.class,
                () -> store.consume(" ", "subject", 1, Duration.ofMinutes(1), NOW));
        assertFalse(called.get());
    }
}
