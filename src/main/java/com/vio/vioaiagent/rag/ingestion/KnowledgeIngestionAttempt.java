package com.vio.vioaiagent.rag.ingestion;

import java.time.Instant;

/** 一次 worker 尝试的可审计摘要。 */
public record KnowledgeIngestionAttempt(
        String id,
        String jobId,
        int attemptNo,
        AttemptStatus status,
        String errorClass,
        String errorCode,
        String errorMessage,
        long backoffMs,
        long jitterMs,
        String leaseOwner,
        Instant startedAt,
        Instant completedAt) {

    public KnowledgeIngestionAttempt {
        requireBounded(id, 96, "attempt id");
        requireBounded(jobId, 96, "jobId");
        if (errorClass != null) requireBounded(errorClass, 128, "errorClass");
        if (errorCode != null) requireBounded(errorCode, 128, "errorCode");
        if (leaseOwner != null) requireBounded(leaseOwner, 128, "leaseOwner");
        if (errorMessage != null) errorMessage = KnowledgeIngestionErrorSanitizer.sanitize(errorMessage);
        if (id == null || id.isBlank() || jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("attempt id、jobId 不能为空");
        }
        if (attemptNo <= 0) throw new IllegalArgumentException("attemptNo 必须大于 0");
        if (status == null) status = AttemptStatus.RUNNING;
        if (backoffMs < 0 || jitterMs < 0) {
            throw new IllegalArgumentException("backoff/jitter 不能为负数");
        }
        if (status == AttemptStatus.RUNNING && completedAt != null) {
            throw new IllegalArgumentException("RUNNING attempt 不应有 completedAt");
        }
    }

    private static void requireBounded(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(name + " 长度或字符不合法");
        }
    }

    public enum AttemptStatus { RUNNING, SUCCEEDED, FAILED, ABANDONED }
}
