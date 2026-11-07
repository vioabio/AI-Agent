package com.vio.vioaiagent.memory;

import java.time.Instant;

/** 用户级长期记忆的脱敏持久化事实；正文只以 ciphertext 形式保存。 */
public record UserMemoryEntry(
        String memoryId,
        String userId,
        String conversationId,
        String type,
        String contentDigest,
        String ciphertext,
        String keyVersion,
        String tenantId,
        String sourceMessageId,
        double confidence,
        String consentId,
        Instant effectiveFrom,
        Instant expiresAt,
        Instant deletedAt,
        Instant createdAt,
        Instant updatedAt) {

    public UserMemoryEntry {
        text(memoryId, "memoryId", 128);
        text(userId, "userId", 128);
        text(type, "type", 32);
        text(contentDigest, "contentDigest", 128);
        text(ciphertext, "ciphertext", 200_000);
        text(keyVersion, "keyVersion", 64);
        if (tenantId != null) text(tenantId, "tenantId", 128);
        if (sourceMessageId != null) text(sourceMessageId, "sourceMessageId", 128);
        text(consentId, "consentId", 128);
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence 必须在 0..1");
        }
        if (effectiveFrom == null || expiresAt == null || !expiresAt.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("记忆有效期无效");
        }
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public boolean activeAt(Instant now) {
        Instant point = now == null ? Instant.now() : now;
        return deletedAt == null && !point.isBefore(effectiveFrom) && point.isBefore(expiresAt);
    }

    private static void text(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(name + " 长度或字符不合法");
        }
    }
}
