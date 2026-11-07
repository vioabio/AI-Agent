package com.vio.vioaiagent.rag.domain;

import java.time.Instant;
import java.util.Map;

/** 文档内容版本，checksum 是摄取去重和审计的稳定依据。 */
public record DocumentVersion(
        String id,
        String documentId,
        String versionNo,
        String checksum,
        String parserVersion,
        String schemaVersion,
        String contentVersion,
        ReviewStatus reviewStatus,
        Instant retrievedAt,
        Instant publishedAt,
        long sizeBytes,
        Map<String, Object> metadata) {

    public DocumentVersion {
        KnowledgeSource.requireText(id, "id");
        KnowledgeSource.requireText(documentId, "documentId");
        KnowledgeSource.requireText(versionNo, "versionNo");
        KnowledgeSource.requireText(checksum, "checksum");
        KnowledgeSource.requireText(parserVersion, "parserVersion");
        KnowledgeSource.requireText(schemaVersion, "schemaVersion");
        if (reviewStatus == null) {
            reviewStatus = ReviewStatus.PENDING;
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes 不能为负数");
        }
        retrievedAt = retrievedAt == null ? Instant.now() : retrievedAt;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public enum ReviewStatus { PENDING, APPROVED, REJECTED, QUARANTINED, EXPIRED }
}

