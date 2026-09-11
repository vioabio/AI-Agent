package com.vio.vioaiagent.security;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 持久 HITL 审批端口。实现必须让 create/decide/expire 使用数据库事务或
 * 等价 CAS；业务层不能依赖 JVM Map 才能保证“第一个合法决定获胜”。
 */
public interface HitlApprovalRepository {

    /** Whether this adapter is backed by shared durable state (versus the
     * compatibility no-op used by direct/unit-test constructors). */
    default boolean durable() {
        return false;
    }

    /** 按 user/run/tool/参数摘要幂等创建或读取当前审批 generation。 */
    CreateResult createOrGet(HitlApproval candidate, Instant now);

    Optional<HitlApproval> find(String approvalId);

    Optional<HitlApproval> findByKey(String userId, String runId, String toolName,
                                     String toolVersion, String parameterDigest);

    /** CAS 决定；已决定的记录按幂等语义原样返回。 */
    DecisionResult decide(String approvalId, String userId, String runId,
                          boolean approved, String decidedBy, String reason, Instant now);

    /** 将到期 PENDING 审批原子改为 EXPIRED，只返回本次真正改动的行。 */
    List<HitlApproval> expireDue(Instant now, String decidedBy);

    /** 可选的审批租约；用于恢复/清理 worker，不能绕过决定 CAS。 */
    Optional<HitlApproval> claim(String approvalId, String owner, Instant now, Duration lease);

    record CreateResult(HitlApproval approval, boolean created) {
        public CreateResult {
            if (approval == null) throw new IllegalArgumentException("approval 不能为空");
        }
    }

    record DecisionResult(HitlApproval approval, boolean changed) {
        public DecisionResult {
            if (approval == null) throw new IllegalArgumentException("approval 不能为空");
        }
    }

    static HitlApprovalRepository noop() {
        return new HitlApprovalRepository() {
            @Override
            public CreateResult createOrGet(HitlApproval candidate, Instant now) {
                return new CreateResult(candidate, true);
            }

            @Override
            public Optional<HitlApproval> find(String approvalId) {
                return Optional.empty();
            }

            @Override
            public Optional<HitlApproval> findByKey(String userId, String runId, String toolName,
                                                     String toolVersion, String parameterDigest) {
                return Optional.empty();
            }

            @Override
            public DecisionResult decide(String approvalId, String userId, String runId,
                                         boolean approved, String decidedBy, String reason, Instant now) {
                throw new HitlApprovalNotFoundException("审批不存在: " + approvalId);
            }

            @Override
            public List<HitlApproval> expireDue(Instant now, String decidedBy) {
                return List.of();
            }

            @Override
            public Optional<HitlApproval> claim(String approvalId, String owner, Instant now, Duration lease) {
                return Optional.empty();
            }
        };
    }
}
