package com.vio.vioaiagent.run;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
@Profile("!prod")
@Primary
public class InMemoryIdempotencyStore implements IdempotencyStore {
    private final ConcurrentMap<String, IdempotencyRecord> records = new ConcurrentHashMap<>();

    @Override
    public synchronized Claim claim(String scope, String key, String requestHash, Duration ttl, Instant now) {
        validate(scope, key, requestHash, ttl);
        Instant point = now == null ? Instant.now() : now;
        String mapKey = scope + "\u0000" + key;
        IdempotencyRecord current = records.get(mapKey);
        if (current != null && !current.validAt(point)) { records.remove(mapKey, current); current = null; }
        if (current == null) {
            IdempotencyRecord pending = new IdempotencyRecord(scope, key, requestHash, 102,
                    Map.of("state", "IN_PROGRESS"), point, point.plus(ttl));
            records.put(mapKey, pending);
            return IdempotencyStore.Claim.acceptedClaim();
        }
        if (!current.requestHash().equals(requestHash)) return IdempotencyStore.Claim.conflict(current);
        return IdempotencyStore.Claim.replay(current);
    }

    @Override
    public synchronized IdempotencyRecord complete(String scope, String key, String requestHash,
                                                    int responseStatus, Map<String, Object> responseBody, Instant now) {
        validate(scope, key, requestHash, Duration.ofSeconds(1));
        Instant created = now == null ? Instant.now() : now;
        IdempotencyRecord record = new IdempotencyRecord(scope, key, requestHash, responseStatus,
                responseBody, created, created.plus(Duration.ofHours(24)));
        String mapKey = scope + "\u0000" + key;
        IdempotencyRecord existing = records.get(mapKey);
        if (existing != null && !existing.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(scope, key);
        }
        records.put(mapKey, record);
        return record;
    }

    @Override
    public int deleteExpired(Instant now, int limit) {
        Instant point = now == null ? Instant.now() : now;
        int max = Math.max(1, limit), deleted = 0;
        for (var item : records.entrySet()) {
            if (deleted >= max) break;
            if (!item.getValue().validAt(point) && records.remove(item.getKey(), item.getValue())) deleted++;
        }
        return deleted;
    }

    private static void validate(String scope, String key, String hash, Duration ttl) {
        if (scope == null || scope.isBlank() || key == null || key.isBlank() || hash == null || hash.isBlank()
                || ttl == null || ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("幂等参数无效");
    }
}
