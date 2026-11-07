package com.vio.vioaiagent.run;

import java.time.Instant;
import java.util.Map;

/** 可排序、可重放、可审计的 Run 事件。eventId 只在同一 run 内单调递增。 */
public record AgentEvent(
        String schemaVersion,
        long eventId,
        AgentEventType type,
        String sessionId,
        String runId,
        String traceId,
        Instant timestamp,
        Map<String, Object> payload) {

    public AgentEvent {
        if (schemaVersion == null || schemaVersion.isBlank()) schemaVersion = "1";
        if (eventId <= 0) throw new IllegalArgumentException("eventId 必须从 1 开始");
        if (type == null) throw new IllegalArgumentException("事件类型不能为空");
        requireText(sessionId, "sessionId");
        requireText(runId, "runId");
        requireText(traceId, "traceId");
        timestamp = timestamp == null ? Instant.now() : timestamp;
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public static AgentEvent of(long eventId, AgentEventType type, String sessionId,
                                String runId, String traceId, Map<String, Object> payload) {
        String schema = AgentEventContractRegistry.isStructured(type)
                ? AgentEventContractRegistry.schemaVersion() : "1";
        return new AgentEvent(schema, eventId, type, sessionId, runId, traceId, Instant.now(), payload);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
    }
}
