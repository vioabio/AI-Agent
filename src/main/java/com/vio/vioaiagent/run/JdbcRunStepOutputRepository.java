package com.vio.vioaiagent.run;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** PostgreSQL implementation for immutable encrypted output of one Run step. */
@Repository
@Profile({"prod", "demo"})
@Primary
public class JdbcRunStepOutputRepository implements RunStepOutputRepository {
    private final JdbcTemplate jdbc;

    public JdbcRunStepOutputRepository(JdbcTemplate jdbc) {
        if (jdbc == null) throw new IllegalArgumentException("jdbc 依赖不能为空");
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public RunStepOutput save(RunStepOutput output) {
        if (output == null) throw new IllegalArgumentException("Run step output 不能为空");
        jdbc.update("""
                INSERT INTO agent_run_step_output (
                    run_id, step_no, executor_version, context_hash, payload_digest,
                    encrypted_payload, key_version, created_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (run_id, step_no) DO NOTHING
                """, output.runId(), output.stepNo(), output.executorVersion(), output.contextHash(),
                output.payloadDigest(), output.encryptedPayload(), output.keyVersion(),
                timestamp(output.createdAt()), timestamp(output.expiresAt()));
        Optional<RunStepOutput> existing = find(output.runId(), output.stepNo());
        if (existing.isEmpty()) {
            throw new IllegalStateException("Run step output 写入后无法读取");
        }
        if (!existing.get().equals(output)) throw new RunStepOutputConflictException();
        return existing.get();
    }

    @Override
    public Optional<RunStepOutput> find(String runId, int stepNo) {
        if (runId == null || runId.isBlank() || stepNo < 0) return Optional.empty();
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT run_id, step_no, executor_version, context_hash, payload_digest,
                           encrypted_payload, key_version, created_at, expires_at
                    FROM agent_run_step_output WHERE run_id = ? AND step_no = ?
                    """, mapper(), runId.trim(), stepNo));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional
    public int deleteExpired(Instant now, int limit) {
        Instant point = now == null ? Instant.now() : now;
        int max = Math.max(1, Math.min(limit, 10_000));
        return jdbc.update("""
                DELETE FROM agent_run_step_output WHERE ctid IN (
                    SELECT ctid FROM agent_run_step_output WHERE expires_at <= ? LIMIT ?
                )
                """, timestamp(point), max);
    }

    private RowMapper<RunStepOutput> mapper() {
        return (rs, row) -> new RunStepOutput(rs.getString("run_id"), rs.getInt("step_no"),
                rs.getString("executor_version"), rs.getString("context_hash"),
                rs.getString("payload_digest"), rs.getString("encrypted_payload"),
                rs.getString("key_version"), instant(rs, "created_at"), instant(rs, "expires_at"));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
