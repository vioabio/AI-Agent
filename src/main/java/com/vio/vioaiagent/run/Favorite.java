package com.vio.vioaiagent.run;

import java.time.Instant;
import java.util.Map;

/** 用户级收藏资源；resourceKey 只保存业务 ID/slug，不保存秘密或任意 URL。 */
public record Favorite(long id, String ownerId, String resourceType, String resourceKey,
                       String label, Map<String, Object> metadata, Instant createdAt, Instant updatedAt) {
    public Favorite {
        if (id < 0) throw new IllegalArgumentException("favorite id 不能为负数");
        require(ownerId, "ownerId"); require(resourceType, "resourceType"); require(resourceKey, "resourceKey");
        if (resourceType.length() > 64 || resourceKey.length() > 256) throw new IllegalArgumentException("收藏字段过长");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }
    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
    }
}
