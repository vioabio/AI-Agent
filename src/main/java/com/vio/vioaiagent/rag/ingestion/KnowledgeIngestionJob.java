package com.vio.vioaiagent.rag.ingestion;

import java.time.Instant;

/**
 * 持久化知识摄取 Job 的公开视图。
 *
 * <p>Job 是摄取状态机的唯一事实来源；{@link KnowledgeIngestionService}
 * 仍负责一次尝试的内容解析，Job service 负责 reservation、重试和死信。
 * payload 不在控制器响应中直接暴露，避免把隔离文件路径回传给调用方。</p>
 */
public record KnowledgeIngestionJob(
        String id,
        String sourceId,
        String contentChecksum,
        String targetKnowledgeReleaseId,
        String ingestionContractVersion,
        String idempotencyKey,
        int replayGeneration,
        String replayOfJobId,
        KnowledgeIngestionJobPayload payload,
        JobStatus status,
        int attemptCount,
        int maxAttempts,
        Instant nextAttemptAt,
        String leaseOwner,
        Instant leaseExpiresAt,
        String resultBatchId,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    public KnowledgeIngestionJob {
        requireText(id, "id");
        requireText(sourceId, "sourceId");
        requireText(contentChecksum, "contentChecksum");
        requireText(targetKnowledgeReleaseId, "targetKnowledgeReleaseId");
        requireText(ingestionContractVersion, "ingestionContractVersion");
        requireText(idempotencyKey, "idempotencyKey");
        requireBounded(id, 96, "id");
        requireBounded(sourceId, 64, "sourceId");
        requireBounded(contentChecksum, 128, "contentChecksum");
        requireBounded(targetKnowledgeReleaseId, 64, "targetKnowledgeReleaseId");
        requireBounded(ingestionContractVersion, 64, "ingestionContractVersion");
        requireBounded(idempotencyKey, 256, "idempotencyKey");
        if (replayOfJobId != null) requireBounded(replayOfJobId, 96, "replayOfJobId");
        if (replayGeneration < 0) {
            throw new IllegalArgumentException("replayGeneration 不能为负数");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload 不能为空");
        }
        status = status == null ? JobStatus.QUEUED : status;
        if (attemptCount < 0) {
            throw new IllegalArgumentException("attemptCount 不能为负数");
        }
        if (maxAttempts < 1 || maxAttempts > 20) {
            throw new IllegalArgumentException("maxAttempts 必须在 1 到 20 之间");
        }
        if (attemptCount > maxAttempts) {
            throw new IllegalArgumentException("attemptCount 不能超过 maxAttempts");
        }
        if ((status == JobStatus.RESERVED || status == JobStatus.PROCESSING)
                && (leaseOwner == null || leaseOwner.isBlank() || leaseExpiresAt == null)) {
            throw new IllegalArgumentException("RESERVED/PROCESSING Job 必须持有有效 lease");
        }
        if (status != JobStatus.RESERVED && status != JobStatus.PROCESSING
                && (leaseOwner != null || leaseExpiresAt != null)) {
            throw new IllegalArgumentException("非执行态 Job 不应保留 lease");
        }
        if (status == JobStatus.RETRY_WAIT && nextAttemptAt == null) {
            throw new IllegalArgumentException("RETRY_WAIT Job 必须有 nextAttemptAt");
        }
        if (status != JobStatus.RETRY_WAIT && nextAttemptAt != null) {
            throw new IllegalArgumentException("只有 RETRY_WAIT Job 可以设置 nextAttemptAt");
        }
        if (status == JobStatus.SUCCEEDED && (resultBatchId == null || resultBatchId.isBlank())) {
            throw new IllegalArgumentException("SUCCEEDED Job 必须有 resultBatchId");
        }
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public boolean terminal() {
        return status == JobStatus.SUCCEEDED || status == JobStatus.DLQ;
    }

    public enum JobStatus {
        QUEUED,
        RESERVED,
        PROCESSING,
        RETRY_WAIT,
        SUCCEEDED,
        DLQ
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }

    private static void requireBounded(String value, int max, String name) {
        if (value.length() > max || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(name + " 长度或字符不合法");
        }
    }
}
