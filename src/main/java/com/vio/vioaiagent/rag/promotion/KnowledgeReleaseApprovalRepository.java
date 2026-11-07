package com.vio.vioaiagent.rag.promotion;

import java.util.Optional;

/** knowledge release approval 的追加式仓储边界。 */
public interface KnowledgeReleaseApprovalRepository {

    ReservationResult createOrGet(KnowledgeReleaseApproval candidate);

    /** 便于离线 producer/迁移脚本使用的兼容别名；仍然是追加式写入。 */
    default ReservationResult save(KnowledgeReleaseApproval candidate) {
        return createOrGet(candidate);
    }

    Optional<KnowledgeReleaseApproval> findByApprovalId(String approvalId);

    default Optional<KnowledgeReleaseApproval> find(String approvalId) {
        return findByApprovalId(approvalId);
    }

    Optional<KnowledgeReleaseApproval> findByNaturalKey(String knowledgeReleaseId,
                                                        String indexReleaseId,
                                                        String evaluationRunId,
                                                        String reportHash);

    /** false 表示实现不可用于发布；promotion 必须 fail-closed。 */
    default boolean durable() {
        return false;
    }

    record ReservationResult(KnowledgeReleaseApproval approval, boolean created) {
        public ReservationResult {
            if (approval == null) throw new IllegalArgumentException("approval 不能为空");
        }
    }

    static KnowledgeReleaseApprovalRepository unavailable() {
        return new KnowledgeReleaseApprovalRepository() {
            @Override
            public ReservationResult createOrGet(KnowledgeReleaseApproval candidate) {
                throw new IllegalStateException("knowledge release approval 仓储不可用");
            }

            @Override
            public Optional<KnowledgeReleaseApproval> findByApprovalId(String approvalId) {
                return Optional.empty();
            }

            @Override
            public Optional<KnowledgeReleaseApproval> findByNaturalKey(String knowledgeReleaseId,
                                                                        String indexReleaseId,
                                                                        String evaluationRunId,
                                                                        String reportHash) {
                return Optional.empty();
            }
        };
    }
}
