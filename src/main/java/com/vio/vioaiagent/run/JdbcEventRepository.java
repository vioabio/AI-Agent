package com.vio.vioaiagent.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 生产环境事件仓储：按 (run_id,event_id) 幂等追加并支持断点游标读取。 */
@Repository
@Profile("prod")
@Primary
public class JdbcEventRepository implements EventRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    /**
     * 锁住 agent_run 行后在同一 SQL 语句内计算并插入下一个序号。
     * SELECT ... FOR UPDATE 与 MAX 聚合处于同一事务，多个实例会按行锁串行化。
     */
    private static final String APPEND_NEXT = """
            WITH locked_run AS (
                SELECT r.id
                FROM agent_run r
                JOIN conversation c ON c.id = r.conversation_id
                WHERE r.id = ? AND c.deleted_at IS NULL
                -- Lock both aggregate and conversation rows.  A delete that
                -- already committed is invisible; a delete racing this
                -- statement waits until the event insert commits.
                FOR UPDATE
            ), next_event AS (
            SELECT r.id AS run_id, COALESCE(MAX(e.event_id), 0) + 1 AS event_id
                FROM locked_run r
                LEFT JOIN agent_event e ON e.run_id = r.id
                GROUP BY r.id
            )
            INSERT INTO agent_event (
                run_id, event_id, schema_version, type, session_id, trace_id,
                event_timestamp, payload
            )
            SELECT run_id, event_id, ?, ?, ?, ?, ?, ?::jsonb
            FROM next_event
            WHERE ?::text IN ('run.completed', 'run.failed', 'run.cancelled', 'run.timed_out')
               OR NOT EXISTS (
                   SELECT 1 FROM agent_event terminal_event
                   WHERE terminal_event.run_id = next_event.run_id
                     AND terminal_event.type IN ('run.completed', 'run.failed', 'run.cancelled', 'run.timed_out')
               )
            RETURNING event_id
            """;

    /** 追加前的可见性锁，防止软删除后旧 worker 继续写入事件。 */
    private static final String LOCK_VISIBLE_RUN = """
            SELECT r.id
            FROM agent_run r
            JOIN conversation c ON c.id = r.conversation_id
            WHERE r.id = ? AND c.deleted_at IS NULL
            FOR UPDATE
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<AgentEvent> mapper = JdbcEventRepository::map;

    public JdbcEventRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
    }

    @Override
    @Transactional
    public AgentEvent append(AgentEvent event) {
        if (event == null) throw new IllegalArgumentException("事件不能为空");
        // 先识别完全相同的重放事件，不能因为该 Run 后来已经进入终态
        // 就破坏 append 的幂等语义。
        Optional<AgentEvent> existingBeforeInsert = findOne(event.runId(), event.eventId());
        if (existingBeforeInsert.isPresent()) {
            if (!existingBeforeInsert.get().equals(event)) {
                throw new IllegalStateException("eventId 重复且内容不一致: " + event.runId() + "/" + event.eventId());
            }
            return existingBeforeInsert.get();
        }
        try {
            jdbc.queryForObject(LOCK_VISIBLE_RUN, String.class, event.runId());
        } catch (EmptyResultDataAccessException ex) {
            throw new IllegalStateException("Run 不存在或会话已删除，无法追加事件: " + event.runId(), ex);
        } catch (DataAccessException ex) {
            throw new IllegalStateException("校验会话可见性失败，无法追加事件: " + event.runId(), ex);
        }
        // The terminal check must happen after acquiring the same run row lock
        // used by appendNext. Checking before the lock leaves a race in which
        // another transaction can commit run.completed while this writer is
        // waiting, then this writer appends a non-terminal event afterwards.
        if (hasTerminalEvent(event.runId()) && !event.type().isTerminal()) {
            throw new IllegalStateException("终态事件后不能追加非终态事件: " + event.runId());
        }
        int inserted = jdbc.update("""
                INSERT INTO agent_event (
                    run_id, event_id, schema_version, type, session_id, trace_id,
                    event_timestamp, payload
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT DO NOTHING
                """, event.runId(), event.eventId(), event.schemaVersion(), event.type().wireName(),
                event.sessionId(), event.traceId(), Timestamp.from(event.timestamp()), toJson(event.payload()));
        if (inserted == 1) return event;

        // 处理并发插入造成的冲突；若内容不同则拒绝，避免静默破坏事件日志。
        Optional<AgentEvent> existing = findOne(event.runId(), event.eventId());
        if (existing.isPresent()) {
            if (!existing.get().equals(event)) {
                throw new IllegalStateException("eventId 重复且内容不一致: " + event.runId() + "/" + event.eventId());
            }
            return existing.get();
        }
        if (hasTerminalEvent(event.runId()) && event.type().isTerminal()) {
            throw new IllegalStateException("一个 Run 只能有一个终态事件: " + event.runId());
        }
        throw new IllegalStateException("事件追加冲突，未找到冲突记录: " + event.runId() + "/" + event.eventId());
    }

    @Override
    public AgentEvent appendNext(AgentEvent event) {
        if (event == null) throw new IllegalArgumentException("事件不能为空");
        Long eventId;
        try {
            eventId = jdbc.queryForObject(APPEND_NEXT, Long.class,
                    event.runId(), event.schemaVersion(), event.type().wireName(),
                    event.sessionId(), event.traceId(), Timestamp.from(event.timestamp()),
                    toJson(event.payload()), event.type().wireName());
        } catch (EmptyResultDataAccessException ex) {
            throw new IllegalStateException("Run 不存在或会话已删除，无法追加事件: " + event.runId(), ex);
        } catch (DataAccessException ex) {
            // 终态唯一索引等数据库约束冲突必须向上暴露，不能回退到旧序号。
            throw new IllegalStateException("事件原子追加失败: " + event.runId(), ex);
        }
        if (eventId == null || eventId <= 0) {
            throw new IllegalStateException("数据库未返回有效事件序号: " + event.runId());
        }
        return new AgentEvent(event.schemaVersion(), eventId, event.type(), event.sessionId(),
                event.runId(), event.traceId(), event.timestamp(), event.payload());
    }

    @Override
    public List<AgentEvent> findAfter(String runId, long afterEventId) {
        if (runId == null || runId.isBlank()) return List.of();
        return jdbc.query("""
                SELECT * FROM agent_event
                WHERE run_id = ? AND event_id > ?
                ORDER BY event_id ASC
                """, mapper, runId, Math.max(0, afterEventId));
    }

    @Override
    public long lastEventId(String runId) {
        Long value = jdbc.queryForObject("SELECT COALESCE(MAX(event_id), 0) FROM agent_event WHERE run_id = ?",
                Long.class, runId);
        return value == null ? 0 : value;
    }

    @Override
    public boolean hasTerminalEvent(String runId) {
        Boolean value = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM agent_event
                    WHERE run_id = ? AND type IN ('run.completed','run.failed','run.cancelled','run.timed_out')
                )
                """, Boolean.class, runId);
        return Boolean.TRUE.equals(value);
    }

    @Override
    public long retainedFrom(String runId) {
        Long value = jdbc.queryForObject("SELECT MIN(event_id) FROM agent_event WHERE run_id = ?",
                Long.class, runId);
        return value == null ? 1 : value;
    }

    @Override
    public int purgeBefore(String runId, long eventIdExclusive, int batchSize) {
        return purgeBefore(runId, "run-events", eventIdExclusive, batchSize);
    }

    @Override
    @Transactional
    public int purgeBefore(String runId, String retentionClass, long eventIdExclusive, int batchSize) {
        if (runId == null || runId.isBlank() || eventIdExclusive <= 1) return 0;
        if (retentionClass == null || retentionClass.isBlank()) {
            throw new IllegalArgumentException("retentionClass 不能为空");
        }
        int bounded = Math.max(1, Math.min(batchSize, 10_000));
        int deleted = jdbc.update("""
                DELETE FROM agent_event WHERE ctid IN (
                    SELECT ctid FROM agent_event
                    WHERE run_id = ? AND event_id < ?
                      AND type NOT IN ('run.completed','run.failed','run.cancelled','run.timed_out')
                    ORDER BY event_id LIMIT ?
                )
                """, runId, eventIdExclusive, bounded);
        if (deleted > 0) {
            jdbc.update("""
                    INSERT INTO agent_event_retention
                        (run_id, retention_class, retained_from_event_id, last_cleanup_at,
                         deleted_event_count, updated_at)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT (run_id) DO UPDATE SET
                        retention_class = EXCLUDED.retention_class,
                        retained_from_event_id = GREATEST(agent_event_retention.retained_from_event_id, EXCLUDED.retained_from_event_id),
                        last_cleanup_at = EXCLUDED.last_cleanup_at,
                        deleted_event_count = agent_event_retention.deleted_event_count + EXCLUDED.deleted_event_count,
                        updated_at = EXCLUDED.updated_at
                    """, runId, retentionClass.trim(), retainedFrom(runId), deleted);
        }
        return deleted;
    }

    /**
     * 按事件时间批量清理已完成 Run 的历史事件。终态事件和仍未发布的
     * outbox 事件被明确排除，避免清理任务破坏恢复/投递语义。
     */
    @Override
    @Transactional
    public int purgeExpired(String retentionClass, Instant cutoff, int batchSize) {
        if (retentionClass == null || retentionClass.isBlank()) {
            throw new IllegalArgumentException("retentionClass 不能为空");
        }
        if (cutoff == null) throw new IllegalArgumentException("cutoff 不能为空");
        int bounded = Math.max(1, Math.min(batchSize, 10_000));
        List<PurgedEvent> purged = jdbc.query("""
                WITH doomed AS (
                    SELECT e.ctid, e.run_id, e.event_id
                    FROM agent_event e
                    WHERE e.event_timestamp < ?
                      AND e.type NOT IN ('run.completed','run.failed','run.cancelled','run.timed_out')
                      AND EXISTS (
                          SELECT 1 FROM agent_event terminal_event
                          WHERE terminal_event.run_id = e.run_id
                            AND terminal_event.type IN ('run.completed','run.failed','run.cancelled','run.timed_out')
                      )
                      AND NOT EXISTS (
                          SELECT 1 FROM agent_event_outbox pending_outbox
                          WHERE pending_outbox.run_id = e.run_id
                            AND pending_outbox.event_id = e.event_id
                            AND pending_outbox.status NOT IN ('PUBLISHED','DLQ')
                      )
                    ORDER BY e.event_timestamp ASC, e.run_id ASC, e.event_id ASC
                    LIMIT ?
                )
                DELETE FROM agent_event e
                USING doomed d
                WHERE e.ctid = d.ctid
                RETURNING e.run_id, e.event_id
                """, (rs, row) -> new PurgedEvent(rs.getString("run_id"), rs.getLong("event_id")),
                Timestamp.from(cutoff), bounded);
        if (purged.isEmpty()) return 0;

        Map<String, Integer> deletedByRun = new LinkedHashMap<>();
        for (PurgedEvent item : purged) {
            deletedByRun.merge(item.runId(), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> item : deletedByRun.entrySet()) {
            jdbc.update("""
                    INSERT INTO agent_event_retention
                        (run_id, retention_class, retained_from_event_id, expires_at,
                         last_cleanup_at, deleted_event_count, updated_at)
                    VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT (run_id) DO UPDATE SET
                        retention_class = EXCLUDED.retention_class,
                        retained_from_event_id = GREATEST(agent_event_retention.retained_from_event_id, EXCLUDED.retained_from_event_id),
                        expires_at = EXCLUDED.expires_at,
                        last_cleanup_at = EXCLUDED.last_cleanup_at,
                        deleted_event_count = agent_event_retention.deleted_event_count + EXCLUDED.deleted_event_count,
                        updated_at = EXCLUDED.updated_at
                    """, item.getKey(), retentionClass.trim(), retainedFrom(item.getKey()),
                    Timestamp.from(cutoff), item.getValue());
        }
        return purged.size();
    }

    private record PurgedEvent(String runId, long eventId) { }

    private Optional<AgentEvent> findOne(String runId, long eventId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM agent_event WHERE run_id = ? AND event_id = ?", mapper, runId, eventId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("事件 payload 无法序列化", ex);
        }
    }

    private static AgentEvent map(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> payload;
        try {
            String json = rs.getString("payload");
            payload = json == null || json.isBlank()
                    ? Map.of()
                    : new ObjectMapper().readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            throw new SQLException("事件 payload 无法解析", ex);
        }
        return new AgentEvent(
                rs.getString("schema_version"),
                rs.getLong("event_id"),
                AgentEventType.fromWireName(rs.getString("type")),
                rs.getString("session_id"),
                rs.getString("run_id"),
                rs.getString("trace_id"),
                timestamp(rs, "event_timestamp"),
                payload);
    }

    private static Instant timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? Instant.now() : value.toInstant();
    }
}
