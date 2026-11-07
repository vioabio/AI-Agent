package com.vio.vioaiagent.run;

import java.util.Optional;

/** 外部副作用效果账本；调用前先 reserve，调用后再 complete。 */
public interface EffectLedgerRepository {
    /** Manual reconciliation only; automatic workers must never resolve UNKNOWN. */
    default ToolEffectLedgerEntry resolveUnknown(ToolEffectLedgerEntry entry,
                                                  ToolEffectStatus finalStatus,
                                                  String resultDigest, String resultUri) {
        throw new UnsupportedOperationException("副作用 UNKNOWN 核对未配置");
    }

    /** Finds any prior fact for the logical idempotency key, regardless of parameters. */
    default Optional<ToolEffectLedgerEntry> findByIdempotencyKey(String runId, int stepNo,
                                                                  String toolName, String toolVersion,
                                                                  String idempotencyKey) {
        return Optional.empty();
    }

    Optional<ToolEffectLedgerEntry> find(String runId, int stepNo, String toolName,
                                          String toolVersion, String idempotencyKey,
                                          String parameterHash);

    /** 首次预留返回 true；相同键重放返回 false；内容冲突由实现抛出。 */
    boolean reserve(ToolEffectLedgerEntry entry);

    ToolEffectLedgerEntry complete(ToolEffectLedgerEntry entry);
}
