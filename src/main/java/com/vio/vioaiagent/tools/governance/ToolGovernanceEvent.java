package com.vio.vioaiagent.tools.governance;

import java.time.Instant;
import java.util.Map;

/** 面向 Run/前端的结构化工具治理事件。 */
public record ToolGovernanceEvent(
        String eventType,
        String runId,
        String toolName,
        String eventId,
        Instant occurredAt,
        Map<String, Object> attributes) {

    public ToolGovernanceEvent {
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
