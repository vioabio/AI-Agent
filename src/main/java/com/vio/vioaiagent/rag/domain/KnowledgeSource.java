package com.vio.vioaiagent.rag.domain;

import java.time.Instant;

/** 知识来源及其许可、可信等级。来源是文档生命周期的根对象。 */
public record KnowledgeSource(
        String id,
        String sourceUrl,
        String title,
        String license,
        TrustLevel trustLevel,
        String ownerId,
        Instant createdAt,
        Instant updatedAt) {

    public KnowledgeSource {
        requireText(id, "id");
        requireText(sourceUrl, "sourceUrl");
        requireText(license, "license");
        if (trustLevel == null) {
            throw new IllegalArgumentException("trustLevel 不能为空");
        }
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public enum TrustLevel { OFFICIAL, PARTNER, COMMUNITY, UNVERIFIED }

    static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }
}

