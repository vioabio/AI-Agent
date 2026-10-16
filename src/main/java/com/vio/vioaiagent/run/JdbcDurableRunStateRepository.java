package com.vio.vioaiagent.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** PostgreSQL production implementation for N2 checkpoint/lease/effect/cache state. */
@Repository
@Profile({"prod", "demo"})
@Primary
public class JdbcDurableRunStateRepository implements CheckpointRepository,
        EffectLedgerRepository, ModelInvocationCacheRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcDurableRunStateRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper.copy().findAndRegisterModules();
    }

    @Override
    public AgentCheckpoint save(AgentCheckpoint checkpoint) {
        if (checkpoint == null) throw new IllegalArgumentException("checkpoint 不能为空");
        int changed = jdbc.update("""
                INSERT INTO agent_checkpoint
                    (run_id, step_no, executor_version, context_hash, last_event_id, next_action,
                     lease_owner, lease_expires_at, heartbeat_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                ON CONFLICT (run_id, step_no) DO UPDATE SET
                    executor_version = EXCLUDED.executor_version,
                    context_hash = EXCLUDED.context_hash,
                    last_event_id = EXCLUDED.last_event_id,
                    next_action = EXCLUDED.next_action,
                    lease_owner = EXCLUDED.lease_owner,
                    lease_expires_at = EXCLUDED.lease_expires_at,
                    heartbeat_at = EXCLUDED.heartbeat_at,
                    updated_at = EXCLUDED.updated_at,
                    version = agent_checkpoint.version + 1
                WHERE agent_checkpoint.version = ?
                """, checkpoint.runId(), checkpoint.stepNo(), checkpoint.executorVersion(),
                checkpoint.contextHash(), checkpoint.lastEventId(), checkpoint.nextAction(),
                checkpoint.leaseOwner(), timestamp(checkpoint.leaseExpiresAt()),
                timestamp(checkpoint.heartbeatAt()), timestamp(checkpoint.updatedAt()), checkpoint.version());
        if (changed != 1) throw new IllegalStateException("checkpoint CAS 冲突: " + checkpoint.runId());
        return find(checkpoint.runId(), checkpoint.stepNo())
                .orElseThrow(() -> new IllegalStateException("checkpoint 写入后无法读取"));
    }

    @Override
    public Optional<AgentCheckpoint> findLatest(String runId) {
        if (runId == null || runId.isBlank()) return Optional.empty();
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT * FROM agent_checkpoint WHERE run_id = ?
                    ORDER BY step_no DESC, updated_at DESC LIMIT 1
                    """, checkpointMapper(), runId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public List<AgentCheckpoint> findByRun(String runId) {
        if (runId == null || runId.isBlank()) return List.of();
        return jdbc.query("SELECT * FROM agent_checkpoint WHERE run_id = ? ORDER BY step_no ASC",
                checkpointMapper(), runId);
    }

    @Override
    public Optional<RunLease> acquireLease(String runId, String owner, String token, Duration ttl) {
        validateLeaseArgs(runId, owner, token, ttl);
        Instant now = databaseNow();
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    INSERT INTO agent_run_lease
                        (run_id, lease_owner, lease_token, lease_expires_at, heartbeat_at, version, updated_at)
                    VALUES (?, ?, ?, ?, ?, 0, ?)
                    ON CONFLICT (run_id) DO UPDATE SET
                        lease_owner = EXCLUDED.lease_owner,
                        lease_token = EXCLUDED.lease_token,
                        lease_expires_at = EXCLUDED.lease_expires_at,
                        heartbeat_at = EXCLUDED.heartbeat_at,
                        version = agent_run_lease.version + 1,
                        updated_at = EXCLUDED.updated_at
                    WHERE agent_run_lease.lease_expires_at <= clock_timestamp()
                       OR (agent_run_lease.lease_owner = ? AND agent_run_lease.lease_token = ?)
                    RETURNING run_id, lease_owner, lease_token, lease_expires_at, heartbeat_at, version
                    """, leaseMapper(), runId, owner, token, timestamp(now.plus(ttl)), timestamp(now),
                    timestamp(now), owner, token));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<RunLease> heartbeat(String runId, String owner, String token, Duration ttl) {
        validateLeaseArgs(runId, owner, token, ttl);
        Instant now = databaseNow();
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    UPDATE agent_run_lease
                    SET lease_expires_at = ?, heartbeat_at = ?, updated_at = ?, version = version + 1
                    WHERE run_id = ? AND lease_owner = ? AND lease_token = ?
                      AND lease_expires_at > clock_timestamp()
                    RETURNING run_id, lease_owner, lease_token, lease_expires_at, heartbeat_at, version
                    """, leaseMapper(), timestamp(now.plus(ttl)), timestamp(now), timestamp(now),
                    runId, owner, token));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public boolean releaseLease(String runId, String owner, String token) {
        return jdbc.update("DELETE FROM agent_run_lease WHERE run_id = ? AND lease_owner = ? AND lease_token = ?",
                runId, owner, token) == 1;
    }

    @Override
    public Optional<RunLease> currentLease(String runId) {
        if (runId == null || runId.isBlank()) return Optional.empty();
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT run_id, lease_owner, lease_token, lease_expires_at, heartbeat_at, version
                    FROM agent_run_lease WHERE run_id = ?
                    """,
                    leaseMapper(), runId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    // Complete commit/rollback before the caller releases its run monitor or restores its snapshot.
    // Callers must not hold database locks needed by this independent persistence transaction.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T withLease(String runId, String owner, String token, Supplier<T> action) {
        // A directly constructed repository has no transaction proxy and cannot retain a row lock.
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || runId == null || owner == null || token == null || action == null) {
            throw new RunLeaseLostException();
        }
        RunLease lease;
        Instant databaseTime;
        try {
            lease = jdbc.queryForObject("""
                    SELECT run_id, lease_owner, lease_token, lease_expires_at, heartbeat_at, version
                    FROM agent_run_lease WHERE run_id = ? FOR UPDATE
                    """, leaseMapper(), runId);
            // Read after locking: transaction-start time could precede a long lock wait.
            databaseTime = databaseNow();
        } catch (DataAccessException ex) {
            // Fail closed without carrying SQL, parameters or lease tokens in the public exception.
            throw new RunLeaseLostException();
        }
        if (lease == null || !owner.equals(lease.owner())
                || !token.equals(lease.token()) || !lease.activeAt(databaseTime)) {
            throw new RunLeaseLostException();
        }
        return action.get();
    }

    private Instant databaseNow() {
        Timestamp value = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class);
        if (value == null) throw new DataAccessResourceFailureException("数据库时间不可用");
        return value.toInstant();
    }

    @Override
    public Optional<ToolEffectLedgerEntry> find(String runId, int stepNo, String toolName,
                                                 String toolVersion, String idempotencyKey,
                                                 String parameterHash) {
        if (runId == null || runId.isBlank()) return Optional.empty();
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT * FROM agent_effect_ledger
                    WHERE run_id = ? AND step_no = ? AND tool_name = ? AND tool_version = ?
                      AND idempotency_key = ? AND parameter_hash = ?
                    """, effectMapper(), runId, stepNo, toolName, toolVersion, idempotencyKey, parameterHash));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<ToolEffectLedgerEntry> findByIdempotencyKey(String runId, int stepNo,
                                                                  String toolName, String toolVersion,
                                                                  String idempotencyKey) {
        if (runId == null || runId.isBlank()) return Optional.empty();
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT * FROM agent_effect_ledger
                    WHERE run_id = ? AND step_no = ? AND tool_name = ? AND tool_version = ?
                      AND idempotency_key = ? ORDER BY updated_at DESC LIMIT 1
                    """, effectMapper(), runId, stepNo, toolName, toolVersion, idempotencyKey));
        } catch (EmptyResultDataAccessException ex) { return Optional.empty(); }
    }

    @Override
    public boolean reserve(ToolEffectLedgerEntry entry) {
        if (entry == null) throw new IllegalArgumentException("副作用账本项不能为空");
        int changed = jdbc.update("""
                INSERT INTO agent_effect_ledger
                    (run_id, step_no, tool_name, tool_version, idempotency_key, parameter_hash,
                     status, result_digest, result_uri, approval_id, started_at, completed_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (run_id, step_no, tool_name, tool_version, idempotency_key, parameter_hash)
                DO NOTHING
                """, entry.runId(), entry.stepNo(), entry.toolName(), entry.toolVersion(),
                entry.idempotencyKey(), entry.parameterHash(), entry.status().name(), entry.resultDigest(),
                entry.resultUri(), entry.approvalId(), timestamp(entry.startedAt()), timestamp(entry.completedAt()),
                timestamp(entry.updatedAt()));
        return changed == 1;
    }

    @Override
    public ToolEffectLedgerEntry complete(ToolEffectLedgerEntry entry) {
        if (entry == null) throw new IllegalArgumentException("副作用账本项不能为空");
        int changed = jdbc.update("""
                UPDATE agent_effect_ledger SET status = ?, result_digest = ?, result_uri = ?, approval_id = ?,
                    completed_at = ?, updated_at = ?
                WHERE run_id = ? AND step_no = ? AND tool_name = ? AND tool_version = ?
                  AND idempotency_key = ? AND parameter_hash = ? AND status = 'STARTED'
                """, entry.status().name(), entry.resultDigest(), entry.resultUri(), entry.approvalId(),
                timestamp(entry.completedAt()), timestamp(entry.updatedAt()), entry.runId(), entry.stepNo(),
                entry.toolName(), entry.toolVersion(), entry.idempotencyKey(), entry.parameterHash());
        if (changed != 1) {
            Optional<ToolEffectLedgerEntry> existing = find(entry.runId(), entry.stepNo(), entry.toolName(),
                    entry.toolVersion(), entry.idempotencyKey(), entry.parameterHash());
            if (existing.isPresent() && existing.get().status() == entry.status()
                    && java.util.Objects.equals(existing.get().resultDigest(), entry.resultDigest())
                    && java.util.Objects.equals(existing.get().resultUri(), entry.resultUri())) {
                return existing.get();
            }
            throw new IllegalStateException("副作用未预留或已结算");
        }
        return entry;
    }

    @Override
    public ToolEffectLedgerEntry resolveUnknown(ToolEffectLedgerEntry entry, ToolEffectStatus finalStatus,
                                                String resultDigest, String resultUri) {
        if (entry == null || (finalStatus != ToolEffectStatus.SUCCEEDED && finalStatus != ToolEffectStatus.FAILED)
                || resultDigest == null || resultDigest.isBlank()) {
            throw new IllegalArgumentException("UNKNOWN 核对参数无效");
        }
        int changed = jdbc.update("""
                UPDATE agent_effect_ledger SET status = ?, result_digest = ?, result_uri = ?,
                    completed_at = clock_timestamp(), updated_at = clock_timestamp()
                WHERE run_id = ? AND step_no = ? AND tool_name = ? AND tool_version = ?
                  AND idempotency_key = ? AND parameter_hash = ? AND status = 'UNKNOWN'
                """, finalStatus.name(), resultDigest, resultUri, entry.runId(), entry.stepNo(),
                entry.toolName(), entry.toolVersion(), entry.idempotencyKey(), entry.parameterHash());
        if (changed != 1) throw new IllegalStateException("副作用不是 UNKNOWN 状态");
        return find(entry.runId(), entry.stepNo(), entry.toolName(), entry.toolVersion(),
                entry.idempotencyKey(), entry.parameterHash()).orElseThrow();
    }

    @Override
    public Optional<ModelInvocationCacheEntry> findValid(String runId, int stepNo, String requestHash,
                                                         Instant now) {
        if (runId == null || runId.isBlank()) return Optional.empty();
        Instant point = now == null ? Instant.now() : now;
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT * FROM model_invocation_cache
                    WHERE run_id = ? AND step_no = ? AND request_hash = ? AND expires_at > ?
                    """, modelMapper(), runId, stepNo, requestHash, timestamp(point)));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public ModelInvocationCacheEntry save(ModelInvocationCacheEntry entry) {
        if (entry == null) throw new IllegalArgumentException("模型缓存项不能为空");
        jdbc.update("""
                INSERT INTO model_invocation_cache
                    (run_id, step_no, request_hash, model_version, prompt_version, response_digest,
                     encrypted_response, artifact_uri, usage, key_version, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                ON CONFLICT (run_id, step_no, request_hash) DO UPDATE SET
                    model_version = EXCLUDED.model_version, prompt_version = EXCLUDED.prompt_version,
                    response_digest = EXCLUDED.response_digest, encrypted_response = EXCLUDED.encrypted_response,
                    artifact_uri = EXCLUDED.artifact_uri, usage = EXCLUDED.usage,
                    key_version = EXCLUDED.key_version, expires_at = EXCLUDED.expires_at
                """, entry.runId(), entry.stepNo(), entry.requestHash(), entry.modelVersion(),
                entry.promptVersion(), entry.responseDigest(), entry.encryptedResponse(), entry.artifactUri(),
                json(entry.usage()), entry.keyVersion(), timestamp(entry.createdAt()), timestamp(entry.expiresAt()));
        return entry;
    }

    @Override
    public int deleteExpired(Instant now, int limit) {
        Instant point = now == null ? Instant.now() : now;
        int bounded = Math.max(1, Math.min(limit, 10_000));
        return jdbc.update("""
                DELETE FROM model_invocation_cache WHERE ctid IN (
                    SELECT ctid FROM model_invocation_cache WHERE expires_at <= ? LIMIT ?
                )
                """, timestamp(point), bounded);
    }

    private Optional<AgentCheckpoint> find(String runId, int stepNo) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM agent_checkpoint WHERE run_id = ? AND step_no = ?",
                    checkpointMapper(), runId, stepNo));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    private RowMapper<AgentCheckpoint> checkpointMapper() {
        return (rs, row) -> new AgentCheckpoint(rs.getString("run_id"), rs.getInt("step_no"),
                rs.getString("executor_version"), rs.getString("context_hash"), rs.getLong("last_event_id"),
                rs.getString("next_action"), rs.getString("lease_owner"), instant(rs, "lease_expires_at"),
                instant(rs, "heartbeat_at"), instant(rs, "updated_at"), rs.getLong("version"));
    }

    private RowMapper<RunLease> leaseMapper() {
        return (rs, row) -> new RunLease(rs.getString("run_id"), rs.getString("lease_owner"),
                rs.getString("lease_token"), instant(rs, "lease_expires_at"),
                instant(rs, "heartbeat_at"), rs.getLong("version"));
    }

    private RowMapper<ToolEffectLedgerEntry> effectMapper() {
        return (rs, row) -> new ToolEffectLedgerEntry(rs.getString("run_id"), rs.getInt("step_no"),
                rs.getString("tool_name"), rs.getString("tool_version"), rs.getString("idempotency_key"),
                rs.getString("parameter_hash"), ToolEffectStatus.valueOf(rs.getString("status")),
                rs.getString("result_digest"), rs.getString("result_uri"), rs.getString("approval_id"),
                instant(rs, "started_at"), instant(rs, "completed_at"), instant(rs, "updated_at"));
    }

    private RowMapper<ModelInvocationCacheEntry> modelMapper() {
        return (rs, row) -> {
            Map<String, Object> usage;
            try {
                String raw = rs.getString("usage");
                usage = raw == null || raw.isBlank() ? Map.of() : mapper.readValue(raw, MAP_TYPE);
            } catch (Exception ex) {
                throw new SQLException("模型缓存 usage 无法解析", ex);
            }
            return new ModelInvocationCacheEntry(rs.getString("run_id"), rs.getInt("step_no"),
                    rs.getString("request_hash"), rs.getString("model_version"),
                    rs.getString("prompt_version"), rs.getString("response_digest"),
                    rs.getString("encrypted_response"), rs.getString("artifact_uri"), usage,
                    rs.getString("key_version"), instant(rs, "created_at"), instant(rs, "expires_at"));
        };
    }

    private String json(Map<String, Object> value) {
        try { return mapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (JsonProcessingException ex) { throw new IllegalArgumentException("usage 无法序列化", ex); }
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static void validateLeaseArgs(String runId, String owner, String token, Duration ttl) {
        if (runId == null || runId.isBlank() || owner == null || owner.isBlank() || token == null
                || token.isBlank() || ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("租约参数无效");
        }
    }
}
