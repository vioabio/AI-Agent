package com.vio.vioaiagent.memory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 用户级长期记忆持久化边界，所有方法都必须带 userId。 */
public interface UserMemoryRepository {
    UserMemoryEntry save(UserMemoryEntry entry);
    Optional<UserMemoryEntry> findByIdForUser(String memoryId, String userId);
    List<UserMemoryEntry> findActiveForUser(String userId, Instant now, int limit);
    boolean deleteForUser(String memoryId, String userId, Instant deletedAt);
    List<UserMemoryEntry> exportForUser(String userId, Instant now, int limit);
    int purgeExpired(Instant now, int limit);
}
