package com.vio.vioaiagent.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@Repository
@Profile({"prod", "demo"})
@Primary
public class JdbcIdempotencyStore implements IdempotencyStore {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcIdempotencyStore(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper.copy().findAndRegisterModules(); }

    @Override
    public Claim claim(String scope, String key, String requestHash, Duration ttl, Instant now) {
        validate(scope, key, requestHash, ttl);
        Instant point = now == null ? Instant.now() : now;
        // 先清掉已过期占位，让过期的 IN_PROGRESS 不阻塞新的首次请求。
        jdbc.update("DELETE FROM api_idempotency_record WHERE scope=? AND idempotency_key=? AND expires_at <= ?",
                scope, key, timestamp(point));
        Optional<IdempotencyRecord> current = find(scope, key);
        if (current.isEmpty()) {
            // 首次占位：以 INSERT 是否真正插入作为"取得所有权"的判据。
            //
            // 原实现插入后重新读取，再按 hash 相等返回 replay，导致**首次**请求
            // 就被调用方（createRun/resolveCancel）判定为"另一实例仍在处理"，
            // 从而抛 IDEMPOTENCY_IN_PROGRESS，永远建不出资源（S0/B04）。
            // 内存实现 InMemoryIdempotencyStore 首次返回 acceptedClaim()，
            // 这里必须与之保持一致：accepted 表示"本次调用获得占位所有权"。
            int inserted = jdbc.update("""
                    INSERT INTO api_idempotency_record(scope, idempotency_key, request_hash, response_status,
                        response_body, created_at, expires_at)
                    VALUES (?, ?, ?, 102, '{"state":"IN_PROGRESS"}'::jsonb, ?, ?)
                    ON CONFLICT (scope, idempotency_key) DO NOTHING
                    """, scope, key, requestHash, timestamp(point), timestamp(point.plus(ttl)));
            if (inserted == 1) return Claim.acceptedClaim();
            // 未插入说明并发实例已抢先占位；回读后按 hash 判定 replay/conflict，
            // 不能把"没抢到"当成"没有记录"。
            current = find(scope, key);
            if (current.isEmpty()) {
                // 极端竞态（占位行在本事务间隙被删除）：不返回 accepted，
                // 否则两个实例会同时认为自己拥有该幂等键。
                return Claim.replay(new IdempotencyRecord(scope, key, requestHash, 102,
                        Map.of("state", "IN_PROGRESS"), point, point.plus(ttl)));
            }
        }
        return current.get().requestHash().equals(requestHash) ? Claim.replay(current.get()) : Claim.conflict(current.get());
    }

    @Override
    public IdempotencyRecord complete(String scope, String key, String requestHash, int responseStatus,
                                      Map<String, Object> responseBody, Instant now) {
        validate(scope, key, requestHash, Duration.ofSeconds(1));
        Instant created = now == null ? Instant.now() : now;
        IdempotencyRecord record = new IdempotencyRecord(scope, key, requestHash, responseStatus, responseBody,
                created, created.plus(Duration.ofHours(24)));
        int updated = jdbc.update("""
                UPDATE api_idempotency_record SET response_status=?, response_body=?::jsonb,
                    created_at=?, expires_at=?
                WHERE scope=? AND idempotency_key=? AND request_hash=?
                """, responseStatus, json(responseBody), timestamp(created), timestamp(record.expiresAt()),
                scope, key, requestHash);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO api_idempotency_record(scope, idempotency_key, request_hash, response_status,
                        response_body, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                    ON CONFLICT (scope, idempotency_key) DO NOTHING
                    """, scope, key, requestHash, responseStatus, json(responseBody), timestamp(created), timestamp(record.expiresAt()));
        }
        IdempotencyRecord existing = find(scope, key).orElse(record);
        if (!existing.requestHash().equals(requestHash)) throw new IdempotencyConflictException(scope, key);
        return existing;
    }

    @Override
    public int deleteExpired(Instant now, int limit) {
        Instant point = now == null ? Instant.now() : now;
        return jdbc.update("DELETE FROM api_idempotency_record WHERE ctid IN (SELECT ctid FROM api_idempotency_record WHERE expires_at <= ? LIMIT ?)",
                timestamp(point), Math.max(1, Math.min(limit, 10_000)));
    }

    @Override
    public Optional<IdempotencyRecord> find(String scope, String key) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("SELECT * FROM api_idempotency_record WHERE scope=? AND idempotency_key=?",
                    (rs, row) -> {
                        Map<String, Object> body;
                        try { String raw = rs.getString("response_body"); body = raw == null ? Map.of() : mapper.readValue(raw, MAP_TYPE); }
                        catch (Exception ex) { throw new java.sql.SQLException("幂等响应无法解析", ex); }
                        return new IdempotencyRecord(rs.getString("scope"), rs.getString("idempotency_key"),
                                rs.getString("request_hash"), rs.getInt("response_status"), body,
                                instant(rs, "created_at"), instant(rs, "expires_at"));
                    }, scope, key));
        } catch (EmptyResultDataAccessException ex) { return Optional.empty(); }
    }
    private String json(Map<String, Object> body) { try { return mapper.writeValueAsString(body == null ? Map.of() : body); } catch (JsonProcessingException ex) { throw new IllegalArgumentException("幂等响应无法序列化", ex); } }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(java.sql.ResultSet rs, String col) throws java.sql.SQLException { Timestamp value = rs.getTimestamp(col); return value == null ? Instant.now() : value.toInstant(); }
    private static void validate(String scope, String key, String hash, Duration ttl) { if (scope == null || scope.isBlank() || key == null || key.isBlank() || hash == null || hash.isBlank() || ttl == null || ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("幂等参数无效"); }
}
