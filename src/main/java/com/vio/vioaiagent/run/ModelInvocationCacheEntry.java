package com.vio.vioaiagent.run;

import java.time.Instant;
import java.util.Map;

/** 可重放模型步骤的脱敏缓存元数据；真实响应应由受控加密存储承载。 */
public record ModelInvocationCacheEntry(
        String runId,
        int stepNo,
        String requestHash,
        String modelVersion,
        String promptVersion,
        String responseDigest,
        String encryptedResponse,
        String artifactUri,
        Map<String, Object> usage,
        String keyVersion,
        Instant createdAt,
        Instant expiresAt) {

    public ModelInvocationCacheEntry {
        require(runId, "runId");
        if (stepNo < 0) throw new IllegalArgumentException("stepNo 不能为负数");
        requireDigest(requestHash, "requestHash");
        require(modelVersion, "modelVersion");
        require(promptVersion, "promptVersion");
        requireDigest(responseDigest, "responseDigest");
        boolean hasEncryptedResponse = encryptedResponse != null && !encryptedResponse.isBlank();
        boolean hasArtifact = artifactUri != null && !artifactUri.isBlank();
        if (hasEncryptedResponse == hasArtifact) {
            throw new IllegalArgumentException("模型缓存必须且只能包含 encryptedResponse 或 artifactUri 之一");
        }
        usage = usage == null ? Map.of() : Map.copyOf(usage);
        createdAt = createdAt == null ? Instant.now() : createdAt;
        if (expiresAt == null || !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt 必须晚于 createdAt");
        }
    }

    public boolean expired(Instant now) {
        return !expiresAt.isAfter(now == null ? Instant.now() : now);
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
    }

    private static void requireDigest(String value, String field) {
        require(value, field);
        if (!value.matches("^[0-9a-fA-F]{64}$")) {
            throw new IllegalArgumentException(field + " 必须是 64 位 SHA-256 十六进制摘要");
        }
    }
}
