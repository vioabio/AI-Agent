package com.vio.vioaiagent.rag.domain;

import java.time.Instant;

/** 文档的稳定身份；内容变化通过 DocumentVersion 表达。 */
public record KnowledgeDocument(
        String id,
        String sourceId,
        String canonicalKey,
        String language,
        String gameReleaseId,
        String battleFormatId,
        String rulesetVersionId,
        Instant effectiveFrom,
        Instant effectiveTo,
        Instant createdAt) {

    public KnowledgeDocument {
        KnowledgeSource.requireText(id, "id");
        KnowledgeSource.requireText(sourceId, "sourceId");
        KnowledgeSource.requireText(canonicalKey, "canonicalKey");
        KnowledgeSource.requireText(language, "language");
        if (effectiveFrom != null && effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveTo 不能早于 effectiveFrom");
        }
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}

