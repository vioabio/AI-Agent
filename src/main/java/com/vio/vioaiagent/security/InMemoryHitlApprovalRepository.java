package com.vio.vioaiagent.security;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 本地/测试审批仓储。所有状态迁移在同一把 monitor 上完成，语义与 JDBC
 * 实现保持一致；它不对外宣称跨进程耐久性。
 */
@Repository
@Primary
@Profile("!prod")
public class InMemoryHitlApprovalRepository implements HitlApprovalRepository {

    private final Map<String, HitlApproval> entries = new LinkedHashMap<>();

    @Override
    public boolean durable() {
        // 进程内仍不具备跨重启/跨实例持久性，但它实现了与 JDBC 相同的
        // CAS/租约语义，供 local/test 验证正式审批路径。
        return true;
    }

    @Override
    public synchronized CreateResult createOrGet(HitlApproval candidate, Instant now) {
        validateCandidate(candidate);
        Instant point = requireNow(now);
        Optional<HitlApproval> current = latestByKey(candidate);
        if (current.isPresent()) {
            HitlApproval value = current.get();
            if (value.decision() == HitlApproval.Decision.PENDING
                    && value.expiresAt().isAfter(point)) {
                return new CreateResult(value, false);
            }
            // 同一 run/参数的审批在有效期内可安全重放；否则工具重试会
            // 在刚批准后立刻生成第二张审批卡，导致永远无法继续执行。
            if (value.decision() == HitlApproval.Decision.APPROVED
                    && value.expiresAt().isAfter(point)) {
                return new CreateResult(value, false);
            }
            if (value.decision() == HitlApproval.Decision.PENDING
                    && !value.expiresAt().isAfter(point)) {
                expire(value, point, "system");
            }
            // APPROVED/DENIED/EXPIRED are historical rows. A new invocation
            // receives a fresh approval id instead of mutating that history.
        }
        entries.put(candidate.approvalId(), candidate);
        return new CreateResult(candidate, true);
    }

    @Override
    public synchronized Optional<HitlApproval> find(String approvalId) {
        return approvalId == null || approvalId.isBlank()
                ? Optional.empty() : Optional.ofNullable(entries.get(approvalId.trim()));
    }

    @Override
    public synchronized Optional<HitlApproval> findByKey(String userId, String runId, String toolName,
                                                           String toolVersion, String parameterDigest) {
        if (isBlank(userId) || isBlank(runId) || isBlank(toolName)
                || isBlank(toolVersion) || isBlank(parameterDigest)) return Optional.empty();
        return entries.values().stream()
                .filter(value -> value.userId().equals(userId.trim()))
                .filter(value -> value.runId().equals(runId.trim()))
                .filter(value -> value.toolName().equals(toolName.trim()))
                .filter(value -> value.toolVersion().equals(toolVersion.trim()))
                .filter(value -> value.parameterDigest().equals(parameterDigest.trim()))
                .max(Comparator.comparing(HitlApproval::createdAt).thenComparing(HitlApproval::approvalId));
    }

    @Override
    public synchronized DecisionResult decide(String approvalId, String userId, String runId,
                                               boolean approved, String decidedBy, String reason, Instant now) {
        requireText(approvalId, "approvalId");
        requireText(userId, "userId");
        requireText(runId, "runId");
        Instant point = requireNow(now);
        HitlApproval current = entries.get(approvalId.trim());
        if (current == null) throw new HitlApprovalNotFoundException("审批不存在: " + approvalId);
        if (!current.userId().equals(userId.trim()) || !current.runId().equals(runId.trim())) {
            throw new HitlApprovalConflictException("审批上下文不匹配: " + approvalId);
        }
        if (current.decision() == HitlApproval.Decision.PENDING
                && !current.expiresAt().isAfter(point)) {
            HitlApproval expired = expire(current, point, "system");
            return new DecisionResult(expired, true);
        }
        if (current.decision() != HitlApproval.Decision.PENDING) {
            return new DecisionResult(current, false);
        }
        String actor = isBlank(decidedBy) ? userId.trim() : bounded(decidedBy.trim(), 128, "decidedBy");
        String safeReason = safeReason(reason, approved ? "用户批准工具调用" : "用户拒绝工具调用");
        HitlApproval decided = new HitlApproval(current.approvalId(), current.userId(), current.runId(),
                current.toolName(), current.toolVersion(), current.parameterDigest(), current.riskLevel(),
                current.expiresAt(), approved ? HitlApproval.Decision.APPROVED : HitlApproval.Decision.DENIED,
                actor, point, safeReason, null, null, current.version() + 1,
                current.createdAt(), point);
        entries.put(decided.approvalId(), decided);
        return new DecisionResult(decided, true);
    }

    @Override
    public synchronized List<HitlApproval> expireDue(Instant now, String decidedBy) {
        Instant point = requireNow(now);
        String actor = isBlank(decidedBy) ? "system" : bounded(decidedBy.trim(), 128, "decidedBy");
        List<HitlApproval> expired = new ArrayList<>();
        for (HitlApproval current : List.copyOf(entries.values())) {
            if (current.decision() == HitlApproval.Decision.PENDING
                    && !current.expiresAt().isAfter(point)) {
                expired.add(expire(current, point, actor));
            }
        }
        return List.copyOf(expired);
    }

    @Override
    public synchronized Optional<HitlApproval> claim(String approvalId, String owner, Instant now, Duration lease) {
        requireText(approvalId, "approvalId");
        requireText(owner, "owner");
        Instant point = requireNow(now);
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease 不合法");
        }
        HitlApproval current = entries.get(approvalId.trim());
        if (current == null || current.decision() != HitlApproval.Decision.PENDING
                || !current.expiresAt().isAfter(point)) return Optional.empty();
        if (current.leaseOwner() != null && current.leaseExpiresAt() != null
                && current.leaseExpiresAt().isAfter(point)
                && !current.leaseOwner().equals(owner.trim())) return Optional.empty();
        String safeOwner = bounded(owner.trim(), 128, "leaseOwner");
        HitlApproval claimed = new HitlApproval(current.approvalId(), current.userId(), current.runId(),
                current.toolName(), current.toolVersion(), current.parameterDigest(), current.riskLevel(),
                current.expiresAt(), current.decision(), null, null, current.decisionReason(), safeOwner,
                point.plus(lease), current.version() + 1, current.createdAt(), point);
        entries.put(claimed.approvalId(), claimed);
        return Optional.of(claimed);
    }

    private Optional<HitlApproval> latestByKey(HitlApproval candidate) {
        return findByKey(candidate.userId(), candidate.runId(), candidate.toolName(), candidate.toolVersion(),
                candidate.parameterDigest());
    }

    private HitlApproval expire(HitlApproval current, Instant now, String actor) {
        HitlApproval expired = new HitlApproval(current.approvalId(), current.userId(), current.runId(),
                current.toolName(), current.toolVersion(), current.parameterDigest(), current.riskLevel(),
                current.expiresAt(), HitlApproval.Decision.EXPIRED, bounded(actor, 128, "decidedBy"), now,
                "审批已过期", null, null, current.version() + 1, current.createdAt(), now);
        entries.put(expired.approvalId(), expired);
        return expired;
    }

    private void validateCandidate(HitlApproval candidate) {
        if (candidate == null) throw new IllegalArgumentException("approval 不能为空");
        if (candidate.decision() != HitlApproval.Decision.PENDING || candidate.decidedBy() != null
                || candidate.decidedAt() != null || candidate.leaseOwner() != null
                || candidate.leaseExpiresAt() != null) {
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
}
