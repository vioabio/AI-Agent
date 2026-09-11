package com.vio.vioaiagent.rag.domain;

import java.time.Instant;

/** 可发布的知识快照；候选版本通过评测后才成为 ACTIVE。 */
public record KnowledgeRelease(
        String id,
        String releaseNo,
        ReleaseStatus status,
        String parserVersion,
        String embeddingModel,
        String dataReleaseId,
        int sampleSize,
        Double recallAt5,
        Double citationAccuracy,
        Double groundedness,
        Instant createdAt,
        Instant publishedAt) {

    public KnowledgeRelease {
        KnowledgeSource.requireText(id, "id");
        KnowledgeSource.requireText(releaseNo, "releaseNo");
        KnowledgeSource.requireText(parserVersion, "parserVersion");
        status = status == null ? ReleaseStatus.CANDIDATE : status;
        if (sampleSize < 0) {
            throw new IllegalArgumentException("sampleSize 不能为负数");
        }
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public enum ReleaseStatus { CANDIDATE, ACTIVE, ARCHIVED, ROLLED_BACK }
}

