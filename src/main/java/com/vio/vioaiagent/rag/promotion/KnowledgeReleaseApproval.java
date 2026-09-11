package com.vio.vioaiagent.rag.promotion;

import java.time.Instant;

/**
 * knowledge release 的独立审批事实。它与 DocumentVersion.reviewStatus 分离，
 * 只允许追加，供发布、回滚和审计链回读。
 */
public record KnowledgeReleaseApproval(
        String approvalId,
        String knowledgeReleaseId,
        String indexReleaseId,
        String dataReleaseId,
        String evaluationRunId,
        String reportHash,
        String reviewer,
        String reason,
        Instant approvedAt,
        String gateVersion) {

    public KnowledgeReleaseApproval {
        approvalId = required(approvalId, "approvalId");
        knowledgeReleaseId = required(knowledgeReleaseId, "knowledgeReleaseId");
        indexReleaseId = required(indexReleaseId, "indexReleaseId");
        dataReleaseId = required(dataReleaseId, "dataReleaseId");
        evaluationRunId = required(evaluationRunId, "evaluationRunId");
        reportHash = required(reportHash, "reportHash");
        reviewer = required(reviewer, "reviewer");
        reason = reason == null || reason.isBlank() ? null : reason.trim();
        if (reason != null && reason.length() > 2000) {
            throw new IllegalArgumentException("reason 长度不能超过 2000");
        }
        if (approvedAt == null) throw new IllegalArgumentException("approvedAt 不能为空");
        gateVersion = gateVersion == null || gateVersion.isBlank() ? "n1b-v1" : gateVersion.trim();
    }

    public KnowledgeReleaseApproval(String approvalId, String knowledgeReleaseId,
                                    String indexReleaseId, String dataReleaseId,
                                    String evaluationRunId, String reportHash,
                                    String reviewer, String reason, Instant approvedAt) {
        this(approvalId, knowledgeReleaseId, indexReleaseId, dataReleaseId, evaluationRunId,
                reportHash, reviewer, reason, approvedAt, "n1b-v1");
    }

    public static KnowledgeReleaseApproval from(ReleasePromotionRequest request,
                                                String approvalId, Instant approvedAt) {
        if (request == null) throw new IllegalArgumentException("request 不能为空");
        return new KnowledgeReleaseApproval(approvalId, request.knowledgeReleaseId(),
                request.indexReleaseId(), request.dataReleaseId(), request.evaluationRunId(),
                request.reportHash(), request.reviewer(), request.approvalReason(), approvedAt,
                "n1b-v1");
    }

    /** N1b 当前只持久化通过的审批；拒绝/撤销以新的审计事实表示。 */
    public ApprovalStatus status() {
        return ApprovalStatus.APPROVED;
    }

    public boolean approved() {
        return true;
    }

    public enum ApprovalStatus { APPROVED }

    private static String required(String value, String name) {
        if (value == null || value.isBlank() || EvaluationReport.isPlaceholder(value)) {
            throw new IllegalArgumentException(name + " 不能为空或占位值");
        }
        return value.trim();
    }
}
