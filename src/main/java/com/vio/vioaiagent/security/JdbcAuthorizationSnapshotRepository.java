package com.vio.vioaiagent.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

@Repository
@Profile({"prod", "demo"})
@Primary
public class JdbcAuthorizationSnapshotRepository implements AuthorizationSnapshotRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public JdbcAuthorizationSnapshotRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }
    @Override @Transactional
    public AuthorizationSnapshot save(AuthorizationSnapshot value) {
        if (value == null) throw new IllegalArgumentException("授权快照不能为空");

        // 先把时间戳归一到 PostgreSQL TIMESTAMPTZ 实际能表达的精度（微秒），
        // 并且**存这个归一化后的值、返回这个归一化后的值**。
        //
        // 原因：Instant.now() 带纳秒，而 TIMESTAMPTZ 只保留微秒。若直接存原对象，
        // 调用方拿到手里的是纳秒对象、数据库里是微秒值，于是
        //「保存后返回值 != 传入值」——首次写入自己的回读就会被判定为不一致，
        // 在真库上表现为授权快照根本无法落地（save 抛「不可变冲突」）。
        // 归一化后，返回值语义变成「数据库里持久化的那份事实」，
        // 调用方用返回值即可与后续回读做可靠比较。
        AuthorizationSnapshot normalized = truncateToMicros(value);
        jdbc.update("""
                INSERT INTO agent_authorization_snapshot
                    (run_id, principal_id, tenant_id, roles_json, allowed_tools_json, policy_version,
                     policy_fingerprint, binding_id, issued_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (run_id) DO NOTHING
                """, normalized.runId(), normalized.principalId(), normalized.tenantId(),
                json(normalized.roles()), json(normalized.allowedTools()), normalized.policyVersion(),
                normalized.policyFingerprint(), normalized.bindingId(),
                Timestamp.from(normalized.issuedAt()),
                normalized.expiresAt() == null ? null : Timestamp.from(normalized.expiresAt()));
        AuthorizationSnapshot stored = findByRunId(normalized.runId())
                .orElseThrow(() -> new IllegalStateException("授权快照写入后不可读"));
        // 不可变比较：同一 run 的既有事实不得被覆盖。
        // 归一化之后，真正的漂移（roles/tools/policyVersion/fingerprint/binding）
        // 仍然会被拒绝，而单纯的纳秒/微秒表示差异不再误判为冲突。
        if (!stored.equals(normalized)) {
            throw new IllegalStateException("授权快照不可变冲突");
        }
        return stored;
    }

    /**
     * 把快照的时间戳截断到 PostgreSQL TIMESTAMPTZ 的微秒精度。
     *
     * <p>只截断（向下取整），因为数据库存储也是截断而非四舍五入。
     */
    private static AuthorizationSnapshot truncateToMicros(AuthorizationSnapshot value) {
        return new AuthorizationSnapshot(value.runId(), value.principalId(), value.tenantId(),
                value.roles(), value.allowedTools(), value.policyVersion(), value.policyFingerprint(),
                value.bindingId(), toMicros(value.issuedAt()), toMicros(value.expiresAt()));
    }

    private static Instant toMicros(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
    }
    @Override public Optional<AuthorizationSnapshot> findByRunId(String runId) {
        if (runId == null || runId.isBlank()) return Optional.empty();
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT run_id, principal_id, tenant_id, roles_json, allowed_tools_json, policy_version,
                           policy_fingerprint, binding_id, issued_at, expires_at
                    FROM agent_authorization_snapshot WHERE run_id = ?
                    """, (rs, row) -> new AuthorizationSnapshot(rs.getString(1), rs.getString(2), rs.getString(3),
                    parse(rs.getString(4)), parse(rs.getString(5)), rs.getString(6), rs.getString(7), rs.getString(8),
                    rs.getTimestamp(9).toInstant(), rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toInstant()), runId));
        } catch (EmptyResultDataAccessException ex) { return Optional.empty(); }
    }
    private String json(Set<String> values) { try { return mapper.writeValueAsString(values.stream().sorted().toList()); } catch (JsonProcessingException ex) { throw new IllegalStateException("授权快照序列化失败", ex); } }
    private Set<String> parse(String value) { try { return new LinkedHashSet<>(Arrays.asList(mapper.readValue(value, String[].class))); } catch (Exception ex) { throw new IllegalStateException("授权快照字段无效", ex); } }
}
