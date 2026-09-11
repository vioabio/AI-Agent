package com.vio.vioaiagent.rag.domain;

import java.time.Instant;

/** 向量索引版本，与知识发布版本分离，便于独立回滚。 */
public record VectorIndexRelease(
        String id,
        String knowledgeReleaseId,
        String vectorStore,
        String embeddingModel,
        int dimension,
        IndexStatus status,
        String indexChecksum,
        Instant createdAt,
        Instant publishedAt) {

    public VectorIndexRelease {
        KnowledgeSource.requireText(id, "id");
        KnowledgeSource.requireText(knowledgeReleaseId, "knowledgeReleaseId");
        KnowledgeSource.requireText(vectorStore, "vectorStore");
        KnowledgeSource.requireText(embeddingModel, "embeddingModel");
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension 必须大于 0");
        }
        status = status == null ? IndexStatus.CANDIDATE : status;
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public enum IndexStatus { CANDIDATE, BUILDING, ACTIVE, FAILED, ROLLED_BACK }
}

