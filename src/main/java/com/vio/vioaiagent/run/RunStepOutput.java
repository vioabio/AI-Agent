package com.vio.vioaiagent.run;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Immutable, encrypted output for one replayable Run step.
 * The payload is intentionally opaque: callers must not persist prompt, policy,
 * token or plaintext answer data in this record.
 */
public record RunStepOutput(
        String runId,
        int stepNo,
        String executorVersion,
        String contextHash,
        String payloadDigest,
        String encryptedPayload,
        String keyVersion,
        Instant createdAt,
        Instant expiresAt) {

    public RunStepOutput {
        require(runId, "runId");
        if (stepNo < 0) throw new IllegalArgumentException("stepNo 不能为负数");
        require(executorVersion, "executorVersion");
        requireDigest(contextHash, "contextHash");
        requireDigest(payloadDigest, "payloadDigest");
        require(encryptedPayload, "encryptedPayload");
        require(keyVersion, "keyVersion");
        createdAt = precision(createdAt == null ? Instant.now() : createdAt);
        expiresAt = precision(expiresAt);
        if (expiresAt == null || !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt 必须晚于 createdAt");
        }
    }

    public boolean expired(Instant now) {
        return !expiresAt.isAfter(now == null ? Instant.now() : now);
    }

    private static Instant precision(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
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
