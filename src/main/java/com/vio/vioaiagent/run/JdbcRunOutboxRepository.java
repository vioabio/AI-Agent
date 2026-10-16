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
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** PostgreSQL outbox：使用 FOR UPDATE SKIP LOCKED 实现多 worker 领取。 */
@Repository
@Profile({"prod", "demo"})
@Primary
public class JdbcRunOutboxRepository implements RunOutboxRepository {
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(5);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcRunOutboxRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper.copy().findAndRegisterModules();
    }

    @Override
    public RunOutboxMessage enqueue(RunOutboxMessage message) {
        if (message == null) throw new IllegalArgumentException("outbox 消息不能为空");
        jdbc.update("""
                INSERT INTO agent_event_outbox
                    (outbox_id, run_id, event_id, event_type, payload, status, attempts,
                     next_attempt_at, created_at, published_at, last_error, claimed_by, claim_expires_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (outbox_id) DO NOTHING
                """, message.outboxId(), message.runId(), message.eventId(), message.eventType(),
                json(message.payload()), message.status().name(), message.attempts(), timestamp(message.nextAttemptAt()),
                timestamp(message.createdAt()), timestamp(message.publishedAt()), message.lastError(),
                message.claimOwner(), timestamp(message.claimExpiresAt()));
        return find(message.outboxId()).orElse(message);
    }

    @Override
    public List<RunOutboxMessage> claimDue(String owner, Instant now, int limit) {
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("owner 不能为空");
        Instant point = now == null ? Instant.now() : now;
        int bounded = Math.max(1, Math.min(limit, 1000));
        jdbc.update("""
                UPDATE agent_event_outbox
                SET status='PENDING', claimed_by=NULL, claim_expires_at=NULL
                WHERE status='CLAIMED' AND claim_expires_at IS NOT NULL AND claim_expires_at <= ?
                """, timestamp(point));
        return jdbc.query("""
                WITH picked AS (
                    SELECT outbox_id FROM agent_event_outbox
                    -- PENDING 是首次投递，立即可领取；next_attempt_at 只用于 FAILED 重试退避。
                    WHERE (status = 'PENDING' OR (status = 'FAILED' AND next_attempt_at <= ?))
                    ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT ?
                )
                UPDATE agent_event_outbox e
                SET status = 'CLAIMED', attempts = e.attempts + 1,
                    claimed_by = ?, claim_expires_at = ?
                FROM picked p
                WHERE e.outbox_id = p.outbox_id
                RETURNING e.*
                """, (rs, row) -> map(rs), timestamp(point), bounded,
                owner, timestamp(point.plus(CLAIM_LEASE)));
    }

    @Override
    public boolean markPublished(String outboxId, String owner, Instant publishedAt) {
        return jdbc.update("""
                UPDATE agent_event_outbox SET status='PUBLISHED', published_at=?, last_error=NULL,
                    claimed_by=NULL, claim_expires_at=NULL
                WHERE outbox_id=? AND status='CLAIMED' AND claimed_by=?
                """, timestamp(publishedAt == null ? Instant.now() : publishedAt), outboxId, owner) == 1;
    }

    @Override
    public boolean markFailed(String outboxId, String owner, String error, Instant nextAttemptAt,
                              boolean deadLetter) {
        return jdbc.update("""
                UPDATE agent_event_outbox SET status=?, next_attempt_at=?, last_error=?,
                    claimed_by=NULL, claim_expires_at=NULL
                WHERE outbox_id=? AND status='CLAIMED' AND claimed_by=?
                """, deadLetter ? "DLQ" : "FAILED", timestamp(nextAttemptAt == null ? Instant.now() : nextAttemptAt),
                error == null ? "" : error, outboxId, owner) == 1;
    }

    @Override
    public Optional<RunOutboxMessage> find(String outboxId) {
        if (outboxId == null || outboxId.isBlank()) return Optional.empty();
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM agent_event_outbox WHERE outbox_id=?", (rs, row) -> map(rs), outboxId));
        } catch (EmptyResultDataAccessException ex) { return Optional.empty(); }
    }

    private RunOutboxMessage map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> payload;
        try {
            String raw = rs.getString("payload");
            payload = raw == null || raw.isBlank() ? Map.of() : mapper.readValue(raw, MAP_TYPE);
        } catch (Exception ex) { throw new java.sql.SQLException("outbox payload 无法解析", ex); }
        return new RunOutboxMessage(rs.getString("outbox_id"), rs.getString("run_id"), rs.getLong("event_id"),
                rs.getString("event_type"), payload,
                RunOutboxMessage.OutboxStatus.valueOf(rs.getString("status")), rs.getInt("attempts"),
                instant(rs, "next_attempt_at"), instant(rs, "created_at"), instant(rs, "published_at"),
                rs.getString("last_error"), rs.getString("claimed_by"), instant(rs, "claim_expires_at"));
    }

    private String json(Map<String, Object> value) {
        try { return mapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (JsonProcessingException ex) { throw new IllegalArgumentException("outbox payload 无法序列化", ex); }
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant();
    }
}
