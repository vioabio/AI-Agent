package com.vio.vioaiagent.run;

import java.time.Instant;

/** 持久会话聚合根；窗口记忆不替代该完整历史。 */
public record Conversation(String id, String userId, String title, Instant createdAt, Instant updatedAt) {
    public Conversation {
        require(id, "id");
        require(userId, "userId");
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
    }
}

