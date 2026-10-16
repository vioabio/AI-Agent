package com.vio.vioaiagent.run;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 生产环境 Run JDBC 仓储。
 *
 * <p>所有查询都通过参数绑定，且按 user_id 做资源隔离。save 使用
 * {@code version} 条件更新（乐观锁 CAS），异步 worker、超时线程和取消请求
 * 只能提交自己读取之后的快照；旧快照不会静默覆盖新状态。</p>
 */
@Repository
@Profile({"prod", "demo"})
@Primary
public class JdbcRunRepository implements AgentRunRepository {
    private static final String INSERT = """
            INSERT INTO agent_run (
                id, session_id, conversation_id, user_id, status, prompt,
                game_release_id, battle_format, ruleset_version_id, language,
                dlc_id, data_release_id, knowledge_release_id, index_release_id, contract_version,
                trace_id, idempotency_key, created_at, started_at, finished_at,
                deadline_at, version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT DO NOTHING
            """;

    private static final String UPDATE = """
            UPDATE agent_run SET
                session_id = ?,
                conversation_id = ?,
                user_id = ?,
                status = ?,
                prompt = ?,
                game_release_id = ?,
                battle_format = ?,
                ruleset_version_id = ?,
                language = ?,
                dlc_id = ?,
                data_release_id = ?,
                knowledge_release_id = ?,
                index_release_id = ?,
                contract_version = ?,
                trace_id = ?,
                idempotency_key = ?,
                started_at = ?,
                finished_at = ?,
                deadline_at = ?,
                version = agent_run.version + 1
            WHERE id = ?
              AND user_id = ?
              AND agent_run.version = ?
              AND (agent_run.status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT')
                   OR agent_run.status = ?)
            """;

    private final JdbcTemplate jdbc;
    private final RowMapper<AgentRun> mapper = JdbcRunRepository::map;

    public JdbcRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AgentRun save(AgentRun run) {
        if (run == null) throw new IllegalArgumentException("Run 不能为空");
        long expectedVersion = run.version();
        int inserted = expectedVersion == 0 ? jdbc.update(INSERT,
                run.id(), run.sessionId(), run.conversationId(), run.userId(), run.status().name(), run.prompt(),
                run.gameReleaseId(), run.battleFormat(), run.rulesetVersionId(), run.language(),
                run.dlcId(), run.dataReleaseId(), run.knowledgeReleaseId(), run.indexReleaseId(), run.contractVersion(),
                run.traceId(), run.idempotencyKey(), timestamp(run.createdAt()), timestamp(run.startedAt()),
                timestamp(run.finishedAt()), timestamp(run.deadlineAt())) : 0;
        if (inserted == 1) {
            // 新建快照的数据库版本就是 0；不在插入后额外执行一次 UPDATE，
            // 避免首次提交被其他实例抢先推进。
            run.markPersistedVersion(0L);
            return run;
        }

        int updated = jdbc.update(UPDATE,
                run.sessionId(), run.conversationId(), run.userId(), run.status().name(), run.prompt(),
                run.gameReleaseId(), run.battleFormat(), run.rulesetVersionId(), run.language(),
                run.dlcId(), run.dataReleaseId(), run.knowledgeReleaseId(), run.indexReleaseId(), run.contractVersion(),
                run.traceId(), run.idempotencyKey(), timestamp(run.startedAt()), timestamp(run.finishedAt()),
                timestamp(run.deadlineAt()), run.id(), run.userId(), expectedVersion, run.status().name());
        if (updated != 1) {
            throw new RunConflictException(run.id(), run.version());
        }
        run.markPersistedVersion(expectedVersion + 1);
        return run;
    }

    @Override
    public Optional<AgentRun> findById(String runId) {
        if (runId == null || runId.isBlank()) return Optional.empty();
        // 会话删除采用软删除；后台恢复/超时路径也必须看见同一归属门禁，
        // 否则 refreshAfterConflict/timeout 会重新取出已删除会话的 Run 并继续写事件。
        return queryOne("""
                SELECT r.* FROM agent_run r
                JOIN conversation c ON c.id = r.conversation_id
                WHERE r.id = ? AND c.deleted_at IS NULL
                """, runId.trim());
    }

    @Override
    public Optional<AgentRun> findByIdForUser(String runId, String userId) {
        if (runId == null || runId.isBlank() || userId == null || userId.isBlank()) return Optional.empty();
        return queryOne("""
                SELECT r.* FROM agent_run r
                JOIN conversation c ON c.id = r.conversation_id
                WHERE r.id = ? AND r.user_id = ? AND c.user_id = ? AND c.deleted_at IS NULL
                """, runId, userId, userId);
    }

    @Override
    public Optional<AgentRun> findByIdempotencyKey(String userId, String idempotencyKey) {
        if (userId == null || userId.isBlank() || idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return queryOne("""
                SELECT r.* FROM agent_run r
                JOIN conversation c ON c.id = r.conversation_id
                WHERE r.user_id = ? AND r.idempotency_key = ?
                  AND c.user_id = ? AND c.deleted_at IS NULL
                ORDER BY r.created_at DESC LIMIT 1
                """, userId, idempotencyKey, userId);
    }

    @Override
    public List<AgentRun> findActive() {
        return jdbc.query("""
                SELECT r.* FROM agent_run r
                JOIN conversation c ON c.id = r.conversation_id
                WHERE r.status IN ('CREATED', 'RUNNING', 'WAITING_APPROVAL')
                  AND c.deleted_at IS NULL
                ORDER BY r.created_at
                """, mapper);
    }

    private Optional<AgentRun> queryOne(String sql, Object... args) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, mapper, args));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    private static AgentRun map(ResultSet rs, int rowNum) throws SQLException {
        String statusValue = rs.getString("status");
        AgentRunStatus status;
        try {
            status = AgentRunStatus.valueOf(statusValue);
        } catch (IllegalArgumentException ex) {
            throw new SQLException("未知 Run 状态: " + statusValue, ex);
        }
        return AgentRun.restore(
                rs.getString("id"),
                rs.getString("session_id"),
                rs.getString("conversation_id"),
                rs.getString("user_id"),
                rs.getString("prompt"),
                rs.getString("game_release_id"),
                rs.getString("battle_format"),
                rs.getString("ruleset_version_id"),
                rs.getString("language"),
                rs.getString("dlc_id"),
                rs.getString("data_release_id"),
                rs.getString("knowledge_release_id"),
                rs.getString("index_release_id"),
                rs.getString("contract_version"),
                rs.getString("trace_id"),
                rs.getString("idempotency_key"),
                instant(rs, "created_at"),
                instant(rs, "deadline_at"),
                status,
                instant(rs, "started_at"),
                instant(rs, "finished_at"),
                rs.getLong("version"));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
