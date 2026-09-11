package com.vio.vioaiagent.run;

import java.time.Instant;
import java.util.Map;

/** 会话完整消息；role/content 与 Run 解耦，便于重启后恢复。 */
public record ChatMessage(String id, String conversationId, String runId, String userId,
                          String role, String content, Instant createdAt, Map<String, Object> metadata) {
    public ChatMessage {
        require(id, "id");
        require(conversationId, "conversationId");
        require(userId, "userId");
        require(role, "role");
        require(content, "content");
        createdAt = createdAt == null ? Instant.now() : createdAt;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
    }
}

