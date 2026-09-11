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
@Profile("prod")
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
        jdbc.update("DELETE FROM api_idempotency_record WHERE scope=? AND idempotency_key=? AND expires_at <= ?",
                scope, key, timestamp(point));
        Optional<IdempotencyRecord> current = find(scope, key);
        if (current.isEmpty()) {
            jdbc.update("""
                    INSERT INTO api_idempotency_record(scope, idempotency_key, request_hash, response_status,
                        response_body, created_at, expires_at)
                    VALUES (?, ?, ?, 102, '{"state":"IN_PROGRESS"}'::jsonb, ?, ?)
                    ON CONFLICT (scope, idempotency_key) DO NOTHING
                    """, scope, key, requestHash, timestamp(point), timestamp(point.plus(ttl)));
            current = find(scope, key);
            if (current.isEmpty()) return Claim.acceptedClaim();
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

    private Optional<IdempotencyRecord> find(String scope, String key) {
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
