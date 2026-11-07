package com.vio.vioaiagent.rag.ingestion;

import java.util.List;

/**
 * 一次显式向量索引任务的结果。结果只记录可审计的摘要，不包含向量值或密钥。
 */
public record KnowledgeIndexingResult(
        Status status,
        String documentVersionId,
        String vectorIndexReleaseId,
        int indexedCount,
        int failedCount,
        String embeddingModel,
        int embeddingDimension,
        String indexChecksum,
        List<String> warnings) {

    public KnowledgeIndexingResult {
        status = status == null ? Status.FAILED : status;
        indexedCount = Math.max(0, indexedCount);
        failedCount = Math.max(0, failedCount);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public enum Status {
        /** 所有候选 chunk 都已经写入向量存储并标记 READY。 */
        INDEXED,
        /** 该批次没有可索引的 chunk（例如 checksum 去重）。 */
        SKIPPED,
        /** 至少一次向量写入或状态回写失败。 */
        FAILED
    }
}
