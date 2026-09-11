package com.vio.vioaiagent.rag.domain;

import java.time.Instant;

/** 一次显式摄取任务的审计摘要；不会在应用启动时自动执行。 */
public record IngestionBatch(
        String id,
        String sourceId,
        String idempotencyKey,
        IngestionStatus status,
        String parserVersion,
        String chunkConfig,
        String embeddingModel,
        int acceptedCount,
        int rejectedCount,
        String errorMessage,
        Instant startedAt,
        Instant completedAt) {

    public IngestionBatch {
        KnowledgeSource.requireText(id, "id");
        KnowledgeSource.requireText(sourceId, "sourceId");
        KnowledgeSource.requireText(parserVersion, "parserVersion");
        status = status == null ? IngestionStatus.ACCEPTED : status;
        if (acceptedCount < 0 || rejectedCount < 0) {
            throw new IllegalArgumentException("摄取计数不能为负数");
        }
        startedAt = startedAt == null ? Instant.now() : startedAt;
    }

    public enum IngestionStatus { RECEIVED, VALIDATING, ACCEPTED, QUARANTINED, FAILED }
}

