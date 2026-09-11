package com.vio.vioaiagent.rag.ingestion;

import java.time.Instant;

/** 与 Job 状态同事务写入的 at-least-once 事件。 */
public record KnowledgeIngestionOutboxEvent(
        String id,
        String jobId,
        String eventType,
        String payload,
        OutboxStatus status,
        int publishAttempts,
        Instant nextAttemptAt,
        String lastError,
        Instant createdAt,
        Instant publishedAt) {

    public KnowledgeIngestionOutboxEvent {
        requireBounded(id, 96, "outbox id");
        requireBounded(jobId, 96, "jobId");
        requireBounded(eventType, 128, "eventType");
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("outbox payload 不能为空");
        }
        if (lastError != null) lastError = KnowledgeIngestionErrorSanitizer.sanitize(lastError);
        if (id == null || id.isBlank() || jobId == null || jobId.isBlank()
                || eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("outbox id、jobId、eventType 不能为空");
        }
        if (status == null) status = OutboxStatus.PENDING;
        if (publishAttempts < 0) throw new IllegalArgumentException("publishAttempts 不能为负数");
        if (status == OutboxStatus.PUBLISHED && publishedAt == null) {
            throw new IllegalArgumentException("PUBLISHED outbox 必须有 publishedAt");
        }
        if (status != OutboxStatus.PUBLISHED && publishedAt != null) {
            throw new IllegalArgumentException("未发布 outbox 不应有 publishedAt");
        }
    }

    private static void requireBounded(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(name + " 长度或字符不合法");
        }
    }

    public enum OutboxStatus { PENDING, PUBLISHED, FAILED }
}
