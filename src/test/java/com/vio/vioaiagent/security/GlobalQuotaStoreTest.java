package com.vio.vioaiagent.security;

import com.vio.vioaiagent.config.SecurityProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GlobalQuotaStoreTest {
    @Test
    void inMemoryQuotaIsAtomicAndResetsAtNextBucket() {
        InMemoryGlobalQuotaStore store = new InMemoryGlobalQuotaStore();
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        assertTrue(store.consume("user", "subject", 2, Duration.ofMinutes(1), start).allowed());
        assertTrue(store.consume("user", "subject", 2, Duration.ofMinutes(1), start.plusSeconds(1)).allowed());
        var denied = store.consume("user", "subject", 2, Duration.ofMinutes(1), start.plusSeconds(2));
        assertFalse(denied.allowed());
        assertTrue(denied.retryAfterSeconds(start.plusSeconds(2)) > 0);
        assertTrue(store.consume("user", "subject", 2, Duration.ofMinutes(1), start.plusSeconds(60)).allowed());
    }

    @Test
    void tokenStoreUsesGlobalQuotaWithoutExposingToken() {
        SecurityProperties properties = new SecurityProperties(true,
                Map.of("secret-token-value", "test"), java.util.List.of(), java.util.List.of());
        TokenBinding binding = new TokenBinding("secret-token-value", "default", SetOf.one("tool"), 1, null);
        GlobalQuotaStore quota = new InMemoryGlobalQuotaStore();
        TokenStore store = new TokenStore(properties, quota);
        store.register(binding);
        assertNull(store.consumeRate(binding));
        String rejection = store.consumeRate(binding);
        assertNotNull(rejection);
        assertFalse(rejection.contains("secret-token-value"));
    }

    /** Avoid importing mutable collection helpers in the security test. */
    private static final class SetOf {
        static java.util.Set<String> one(String value) { return java.util.Set.of(value); }
    }
}
