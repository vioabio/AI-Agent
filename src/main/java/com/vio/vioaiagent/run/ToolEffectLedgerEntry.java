package com.vio.vioaiagent.run;

import java.time.Instant;

/**
 * 外部工具调用的效果账本键和值。所有组成幂等键的字段均非空，避免 SQL NULL 唯一键失效。
 */
public record ToolEffectLedgerEntry(
        String runId,
        int stepNo,
        String toolName,
        String toolVersion,
        String idempotencyKey,
        String parameterHash,
        ToolEffectStatus status,
        String resultDigest,
        String resultUri,
        String approvalId,
        Instant startedAt,
        Instant completedAt,
        Instant updatedAt) {

    public ToolEffectLedgerEntry {
        require(runId, "runId");
        if (stepNo < 0) throw new IllegalArgumentException("stepNo 不能为负数");
        require(toolName, "toolName");
        require(toolVersion, "toolVersion");
        require(idempotencyKey, "idempotencyKey");
        require(parameterHash, "parameterHash");
        if (status == null) throw new IllegalArgumentException("status 不能为空");
        startedAt = startedAt == null ? Instant.now() : startedAt;
        updatedAt = updatedAt == null ? startedAt : updatedAt;
    }

    public static ToolEffectLedgerEntry started(String runId, int stepNo, String toolName,
                                                String toolVersion, String idempotencyKey,
                                                String parameterHash) {
        return new ToolEffectLedgerEntry(runId, stepNo, toolName, toolVersion,
                idempotencyKey, parameterHash, ToolEffectStatus.STARTED, null, null,
                null, Instant.now(), null, Instant.now());
    }

    public ToolEffectLedgerEntry complete(ToolEffectStatus finalStatus, String resultDigest,
                                          String resultUri, String approvalId) {
        if (finalStatus != ToolEffectStatus.SUCCEEDED && finalStatus != ToolEffectStatus.FAILED
                && finalStatus != ToolEffectStatus.WAITING_APPROVAL && finalStatus != ToolEffectStatus.UNKNOWN) {
            throw new IllegalArgumentException("完成状态不合法: " + finalStatus);
        }
        Instant now = Instant.now();
        return new ToolEffectLedgerEntry(runId, stepNo, toolName, toolVersion,
                idempotencyKey, parameterHash, finalStatus, resultDigest, resultUri,
                approvalId, startedAt, now, now);
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
    }
}
