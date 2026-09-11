package com.vio.vioaiagent.security;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** PostgreSQL durable HITL approval store with row locks and version CAS. */
@Repository
@Primary
@Profile("prod")
public class JdbcHitlApprovalRepository implements HitlApprovalRepository {

    private final JdbcTemplate jdbc;

    public JdbcHitlApprovalRepository(JdbcTemplate jdbc) {
        if (jdbc == null) throw new IllegalArgumentException("jdbc 不能为空");
        this.jdbc = jdbc;
    }

    @Override
    public boolean durable() {
        return true;
    }

    @Override
    @Transactional
    public CreateResult createOrGet(HitlApproval candidate, Instant now) {
        validateCandidate(candidate);
        Instant point = requireNow(now);
        Optional<HitlApproval> current = findByKeyForUpdate(candidate);
        if (current.isPresent()) {
            HitlApproval value = current.get();
            if (value.decision() == HitlApproval.Decision.PENDING
                    && value.expiresAt().isAfter(point)) {
                return new CreateResult(value, false);
            }
            if (value.decision() == HitlApproval.Decision.APPROVED
                    && value.expiresAt().isAfter(point)) {
                return new CreateResult(value, false);
            }
            if (value.decision() == HitlApproval.Decision.PENDING
                    && !value.expiresAt().isAfter(point)) {
                expireRow(value, point, "system");
            }
        }
        int inserted = jdbc.update("""
                INSERT INTO hitl_approval
                    (approval_id, user_id, run_id, tool_name, tool_version, parameter_digest,
                     risk_level, expires_at, decision, decided_by, decided_at, decision_reason,
                     lease_owner, lease_expires_at, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', NULL, NULL, NULL,
                        NULL, NULL, 0, ?, ?)
                ON CONFLICT DO NOTHING
                """, candidate.approvalId(), candidate.userId(), candidate.runId(), candidate.toolName(),
                candidate.toolVersion(), candidate.parameterDigest(), candidate.riskLevel(),
                timestamp(candidate.expiresAt()), timestamp(candidate.createdAt()), timestamp(candidate.updatedAt()));
        HitlApproval persisted = findByKeyForUpdate(candidate).orElseThrow(() ->
                new IllegalStateException("审批写入后无法回读: " + candidate.approvalId()));
        return new CreateResult(persisted, inserted == 1
                && persisted.approvalId().equals(candidate.approvalId()));
    }

    @Override
    public Optional<HitlApproval> find(String approvalId) {
        if (approvalId == null || approvalId.isBlank()) return Optional.empty();
        return jdbc.query("SELECT " + COLUMNS + " FROM hitl_approval WHERE approval_id = ?",
                this::map, approvalId.trim()).stream().findFirst();
    }

    @Override
    public Optional<HitlApproval> findByKey(String userId, String runId, String toolName,
                                             String toolVersion, String parameterDigest) {
        if (isBlank(userId) || isBlank(runId) || isBlank(toolName)
                || isBlank(toolVersion) || isBlank(parameterDigest)) return Optional.empty();
        return jdbc.query("""
                SELECT %s FROM hitl_approval
                 WHERE user_id = ? AND run_id = ? AND tool_name = ?
                   AND tool_version = ? AND parameter_digest = ?
                 ORDER BY created_at DESC, approval_id DESC LIMIT 1
                """.formatted(COLUMNS), this::map, userId.trim(), runId.trim(), toolName.trim(),
                toolVersion.trim(), parameterDigest.trim()).stream().findFirst();
    }

    @Override
    @Transactional
    public DecisionResult decide(String approvalId, String userId, String runId,
                                 boolean approved, String decidedBy, String reason, Instant now) {
        requireText(approvalId, "approvalId");
        requireText(userId, "userId");
        requireText(runId, "runId");
        Instant point = requireNow(now);
        HitlApproval current = jdbc.query("SELECT " + COLUMNS + " FROM hitl_approval "
                        + "WHERE approval_id = ? FOR UPDATE", this::map, approvalId.trim())
                .stream().findFirst().orElseThrow(() ->
                        new HitlApprovalNotFoundException("审批不存在: " + approvalId));
        if (!current.userId().equals(userId.trim()) || !current.runId().equals(runId.trim())) {
            throw new HitlApprovalConflictException("审批上下文不匹配: " + approvalId);
        }
        if (current.decision() == HitlApproval.Decision.PENDING
                && !current.expiresAt().isAfter(point)) {
            HitlApproval expired = expireRow(current, point, "system");
            return new DecisionResult(expired, true);
        }
        if (current.decision() != HitlApproval.Decision.PENDING) {
            return new DecisionResult(current, false);
        }
        String actor = isBlank(decidedBy) ? userId.trim() : bounded(decidedBy.trim(), 128, "decidedBy");
        String safeReason = safeReason(reason, approved ? "用户批准工具调用" : "用户拒绝工具调用");
        int updated = jdbc.update("""
                UPDATE hitl_approval
                   SET decision = ?, decided_by = ?, decided_at = ?, decision_reason = ?,
                       lease_owner = NULL, lease_expires_at = NULL,
                       version = version + 1, updated_at = ?
                 WHERE approval_id = ? AND decision = 'PENDING' AND version = ?
                """, approved ? "APPROVED" : "DENIED", actor, timestamp(point), safeReason,
                timestamp(point), current.approvalId(), current.version());
        if (updated != 1) throw new HitlApprovalConflictException("审批决定 CAS 冲突: " + approvalId);
        HitlApproval decided = find(current.approvalId()).orElseThrow(() ->
                new IllegalStateException("审批决定后无法回读: " + approvalId));
        return new DecisionResult(decided, true);
    }

    @Override
    @Transactional
    public List<HitlApproval> expireDue(Instant now, String decidedBy) {
        Instant point = requireNow(now);
        String actor = isBlank(decidedBy) ? "system" : bounded(decidedBy.trim(), 128, "decidedBy");
        List<HitlApproval> due = jdbc.query("""
                SELECT %s FROM hitl_approval
                 WHERE decision = 'PENDING' AND expires_at <= ?
                 ORDER BY expires_at, approval_id
                 LIMIT 100 FOR UPDATE SKIP LOCKED
                """.formatted(COLUMNS), this::map, timestamp(point));
        List<HitlApproval> result = new ArrayList<>();
        for (HitlApproval value : due) {
            result.add(expireRow(value, point, actor));
        }
        return List.copyOf(result);
    }

    @Override
    @Transactional
    public Optional<HitlApproval> claim(String approvalId, String owner, Instant now, Duration lease) {
        requireText(approvalId, "approvalId");
        requireText(owner, "owner");
        Instant point = requireNow(now);
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease 不合法");
        }
        String safeOwner = bounded(owner.trim(), 128, "leaseOwner");
        int updated = jdbc.update("""
                UPDATE hitl_approval
                   SET lease_owner = ?, lease_expires_at = ?, version = version + 1, updated_at = ?
                 WHERE approval_id = ? AND decision = 'PENDING' AND expires_at > ?
                   AND (lease_owner IS NULL OR lease_expires_at <= ? OR lease_owner = ?)
                """, safeOwner, timestamp(point.plus(lease)), timestamp(point), approvalId.trim(),
                timestamp(point), timestamp(point), safeOwner);
        return updated == 1 ? find(approvalId.trim()) : Optional.empty();
    }

    private Optional<HitlApproval> findByKeyForUpdate(HitlApproval candidate) {
        return jdbc.query("""
                SELECT %s FROM hitl_approval
                 WHERE user_id = ? AND run_id = ? AND tool_name = ?
                   AND tool_version = ? AND parameter_digest = ?
                 ORDER BY created_at DESC, approval_id DESC LIMIT 1
                 FOR UPDATE
                """.formatted(COLUMNS), this::map, candidate.userId(), candidate.runId(), candidate.toolName(),
                candidate.toolVersion(), candidate.parameterDigest()).stream().findFirst();
    }

    private HitlApproval expireRow(HitlApproval current, Instant now, String actor) {
        String safeActor = bounded(actor, 128, "decidedBy");
        int updated = jdbc.update("""
                UPDATE hitl_approval
                   SET decision = 'EXPIRED', decided_by = ?, decided_at = ?, decision_reason = '审批已过期',
                       lease_owner = NULL, lease_expires_at = NULL, version = version + 1, updated_at = ?
                 WHERE approval_id = ? AND decision = 'PENDING' AND version = ?
                """, safeActor, timestamp(now), timestamp(now), current.approvalId(), current.version());
        if (updated != 1) {
            return find(current.approvalId()).orElseThrow(() ->
                    new HitlApprovalConflictException("审批过期 CAS 冲突: " + current.approvalId()));
        }
        return find(current.approvalId()).orElseThrow(() ->
                new IllegalStateException("审批过期后无法回读: " + current.approvalId()));
    }

    private HitlApproval map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new HitlApproval(rs.getString("approval_id"), rs.getString("user_id"), rs.getString("run_id"),
                rs.getString("tool_name"), rs.getString("tool_version"), rs.getString("parameter_digest"),
                rs.getString("risk_level"), instant(rs.getObject("expires_at")),
                enumValue(rs.getString("decision")), rs.getString("decided_by"), instant(rs.getObject("decided_at")),
                rs.getString("decision_reason"), rs.getString("lease_owner"), instant(rs.getObject("lease_expires_at")),
                rs.getLong("version"), instant(rs.getObject("created_at")), instant(rs.getObject("updated_at")));
    }

    private HitlApproval.Decision enumValue(String value) {
        if (value == null || value.isBlank()) return HitlApproval.Decision.PENDING;
        return HitlApproval.Decision.valueOf(value.toUpperCase(java.util.Locale.ROOT));
    }

    private Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.time.OffsetDateTime offset) return offset.toInstant();
        if (value instanceof Instant point) return point;
        return Instant.parse(value.toString());
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private void validateCandidate(HitlApproval candidate) {
        if (candidate == null || candidate.decision() != HitlApproval.Decision.PENDING
                || candidate.decidedBy() != null || candidate.decidedAt() != null
                || candidate.leaseOwner() != null || candidate.leaseExpiresAt() != null) {
            throw new IllegalArgumentException("新建审批必须处于 PENDING 且无 lease");
        }
    }

    private Instant requireNow(Instant now) {
        return now == null ? Instant.now() : now;
    }

    private String safeReason(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String safe = value.replaceAll("[\\r\\n\\t]", " ")
                .replaceAll("(?i)(password|passwd|secret|token|api[-_ ]?key|authorization)\\s*[:=]\\s*[^\\s,;]+",
                        "$1=[REDACTED]");
        return safe.length() > 2000 ? safe.substring(0, 2000) : safe;
    }

    private String bounded(String value, int max, String field) {
        if (isBlank(value) || value.length() > max || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(field + " 长度或字符不合法");
        }
        return value;
    }

    private void requireText(String value, String field) {
        if (isBlank(value)) throw new IllegalArgumentException(field + " 不能为空");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static final String COLUMNS = "approval_id, user_id, run_id, tool_name, tool_version, "
            + "parameter_digest, risk_level, expires_at, decision, decided_by, decided_at, decision_reason, "
            + "lease_owner, lease_expires_at, version, created_at, updated_at";
}
