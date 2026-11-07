package com.vio.vioaiagent.run;

import java.time.Instant;
import java.util.Map;

/** 事务 outbox 记录；publisher 至少一次投递，消费者按 outboxId 去重。 */
public record RunOutboxMessage(
        String outboxId,
        String runId,
        long eventId,
        String eventType,
        Map<String, Object> payload,
        OutboxStatus status,
        int attempts,
        Instant nextAttemptAt,
        Instant createdAt,
        Instant publishedAt,
        String lastError,
        String claimOwner,
        Instant claimExpiresAt) {

    /** Backward-compatible constructor for producers that do not carry lease metadata. */
    public RunOutboxMessage(String outboxId, String runId, long eventId, String eventType,
                            Map<String, Object> payload, OutboxStatus status, int attempts,
                            Instant nextAttemptAt, Instant createdAt, Instant publishedAt,
                            String lastError) {
        this(outboxId, runId, eventId, eventType, payload, status, attempts, nextAttemptAt,
                createdAt, publishedAt, lastError, null, null);
    }

    public RunOutboxMessage {
        require(outboxId, "outboxId");
        require(runId, "runId");
        if (eventId <= 0) throw new IllegalArgumentException("eventId 必须为正数");
        require(eventType, "eventType");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        if (status == null) status = OutboxStatus.PENDING;
        if (attempts < 0) throw new IllegalArgumentException("attempts 不能为负数");
        createdAt = createdAt == null ? Instant.now() : createdAt;
        nextAttemptAt = nextAttemptAt == null ? createdAt : nextAttemptAt;
        if (claimOwner != null && claimOwner.isBlank()) claimOwner = null;
        if (status != OutboxStatus.CLAIMED) {
            claimOwner = null;
            claimExpiresAt = null;
        }
    }

    public static RunOutboxMessage pending(String outboxId, String runId, long eventId,
                                           String eventType, Map<String, Object> payload) {
        return new RunOutboxMessage(outboxId, runId, eventId, eventType, payload,
                OutboxStatus.PENDING, 0, Instant.now(), Instant.now(), null, null, null, null);
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
    }

    public enum OutboxStatus { PENDING, CLAIMED, PUBLISHED, FAILED, DLQ }
}
