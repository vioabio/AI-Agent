package com.vio.vioaiagent.rag.ingestion;

import java.time.Instant;

/** 达到重试上限或不可重试错误后的人工处理记录。 */
public record KnowledgeIngestionDlqEntry(
        String id,
        String jobId,
        String originalChecksum,
        int lastAttemptNo,
        String errorClass,
        String errorCode,
        String errorMessage,
        Instant retentionUntil,
        String replayedBy,
        String replayReason,
        String replayJobId,
        Instant createdAt,
        Instant replayedAt) {

    public KnowledgeIngestionDlqEntry {
        requireBounded(id, 96, "dlq id");
        requireBounded(jobId, 96, "jobId");
        requireBounded(originalChecksum, 128, "originalChecksum");
        requireBounded(errorClass, 128, "errorClass");
        requireBounded(errorCode, 128, "errorCode");
        errorMessage = KnowledgeIngestionErrorSanitizer.sanitize(errorMessage);
        if (replayedBy != null) requireBounded(replayedBy, 128, "replayedBy");
        if (replayJobId != null) requireBounded(replayJobId, 96, "replayJobId");
        if (replayReason != null && replayReason.length() > 2000) {
            throw new IllegalArgumentException("replayReason 长度不合法");
        }
        if (id == null || id.isBlank() || jobId == null || jobId.isBlank()
                || originalChecksum == null || originalChecksum.isBlank()
                || errorClass == null || errorClass.isBlank()
                || errorCode == null || errorCode.isBlank()
                || errorMessage == null || errorMessage.isBlank()) {
            throw new IllegalArgumentException("DLQ 必填字段不能为空");
        }
        if (lastAttemptNo <= 0) throw new IllegalArgumentException("lastAttemptNo 必须大于 0");
        if (retentionUntil == null) throw new IllegalArgumentException("retentionUntil 不能为空");
        boolean anyReplay = replayedBy != null || replayReason != null || replayJobId != null || replayedAt != null;
        boolean completeReplay = replayedBy != null && replayReason != null && replayJobId != null && replayedAt != null;
        if (anyReplay && !completeReplay) throw new IllegalArgumentException("DLQ replay 字段必须成组存在");
    }

    private static void requireBounded(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(name + " 长度或字符不合法");
        }
    }
}
