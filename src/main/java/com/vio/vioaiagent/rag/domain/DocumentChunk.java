package com.vio.vioaiagent.rag.domain;

import java.util.Map;

/** 可检索的最小证据单元。chunkId 在同一文档版本内必须稳定。 */
public record DocumentChunk(
        String id,
        String documentVersionId,
        int chunkNo,
        String content,
        String contentHash,
        Map<String, Object> metadata,
        String embeddingModel,
        Integer embeddingDimension,
        EmbeddingStatus embeddingStatus) {

    public DocumentChunk {
        KnowledgeSource.requireText(id, "id");
        KnowledgeSource.requireText(documentVersionId, "documentVersionId");
        if (chunkNo < 0) {
            throw new IllegalArgumentException("chunkNo 不能为负数");
        }
        KnowledgeSource.requireText(content, "content");
        KnowledgeSource.requireText(contentHash, "contentHash");
        if (embeddingDimension != null && embeddingDimension <= 0) {
            throw new IllegalArgumentException("embeddingDimension 必须大于 0");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        embeddingStatus = embeddingStatus == null ? EmbeddingStatus.PENDING : embeddingStatus;
    }

    public enum EmbeddingStatus { PENDING, READY, FAILED }
}

