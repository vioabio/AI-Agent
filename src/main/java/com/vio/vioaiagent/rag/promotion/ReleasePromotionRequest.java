package com.vio.vioaiagent.rag.promotion;

import java.time.Instant;

/**
 * N1b promotion 的显式输入。三段 release、evaluationRun 和报告 hash 都是
 * 请求的一等字段，不能从 active 指针或 Controller 默认值补齐。
 */
public record ReleasePromotionRequest(
        String indexReleaseId,
        String knowledgeReleaseId,
        String dataReleaseId,
        String evaluationRunId,
        String reportHash,
        String reviewer,
        String approvalReason,
        Instant requestedAt) {

    public ReleasePromotionRequest {
        indexReleaseId = required(indexReleaseId, "indexReleaseId");
        knowledgeReleaseId = required(knowledgeReleaseId, "knowledgeReleaseId");
        dataReleaseId = required(dataReleaseId, "dataReleaseId");
        evaluationRunId = required(evaluationRunId, "evaluationRunId");
        reportHash = required(reportHash, "reportHash");
        reviewer = required(reviewer, "reviewer");
        if (approvalReason != null && approvalReason.length() > 2000) {
            throw new IllegalArgumentException("approvalReason 长度不能超过 2000");
        }
        approvalReason = approvalReason == null || approvalReason.isBlank()
                ? null : approvalReason.trim();
        requestedAt = requestedAt == null ? Instant.now() : requestedAt;
    }

    /** 常用六字段构造器；请求时间由服务端记录。 */
    public ReleasePromotionRequest(String indexReleaseId, String knowledgeReleaseId,
                                   String dataReleaseId, String evaluationRunId,
                                   String reportHash, String reviewer) {
        this(indexReleaseId, knowledgeReleaseId, dataReleaseId, evaluationRunId,
                reportHash, reviewer, null, Instant.now());
    }

    /** 便于按“知识 release → 索引 release”语义阅读的命名工厂。 */
    public static ReleasePromotionRequest of(String knowledgeReleaseId, String indexReleaseId,
                                             String dataReleaseId, String evaluationRunId,
                                             String reportHash, String reviewer) {
        return new ReleasePromotionRequest(indexReleaseId, knowledgeReleaseId, dataReleaseId,
                evaluationRunId, reportHash, reviewer);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank() || EvaluationReport.isPlaceholder(value)) {
            throw new IllegalArgumentException(name + " 不能为空或占位值");
        }
        return value.trim();
    }
}
