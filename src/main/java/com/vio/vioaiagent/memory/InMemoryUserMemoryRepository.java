package com.vio.vioaiagent.memory;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 本地/测试用户记忆仓储；不会宣称跨进程耐久性。 */
@Repository
@Primary
@Profile("!prod")
public class InMemoryUserMemoryRepository implements UserMemoryRepository {
    private final Map<String, UserMemoryEntry> entries = new LinkedHashMap<>();

    @Override
    public synchronized UserMemoryEntry save(UserMemoryEntry entry) {
        if (entry == null) throw new IllegalArgumentException("记忆不能为空");
        entries.put(entry.memoryId(), entry);
        return entry;
    }

    @Override
    public synchronized Optional<UserMemoryEntry> findByIdForUser(String memoryId, String userId) {
        if (blank(memoryId) || blank(userId)) return Optional.empty();
        return Optional.ofNullable(entries.get(memoryId.trim()))
                .filter(value -> value.userId().equals(userId.trim()));
    }

    @Override
    public synchronized List<UserMemoryEntry> findActiveForUser(String userId, Instant now, int limit) {
        if (blank(userId) || limit <= 0) return List.of();
        Instant point = now == null ? Instant.now() : now;
        return entries.values().stream().filter(value -> value.userId().equals(userId.trim()))
                .filter(value -> value.activeAt(point))
                .sorted(Comparator.comparing(UserMemoryEntry::createdAt).reversed())
                .limit(Math.min(limit, 500)).toList();
    }

    @Override
    public synchronized boolean deleteForUser(String memoryId, String userId, Instant deletedAt) {
        Optional<UserMemoryEntry> current = findByIdForUser(memoryId, userId);
        if (current.isEmpty() || current.get().deletedAt() != null) return false;
        UserMemoryEntry value = current.get();
        Instant point = deletedAt == null ? Instant.now() : deletedAt;
        entries.put(value.memoryId(), new UserMemoryEntry(value.memoryId(), value.userId(), value.conversationId(),
                value.type(), value.contentDigest(), value.ciphertext(), value.keyVersion(), value.tenantId(),
                value.sourceMessageId(), value.confidence(), value.consentId(), value.effectiveFrom(),
                value.expiresAt(), point, value.createdAt(), point));
        return true;
    }

    @Override
    public synchronized List<UserMemoryEntry> exportForUser(String userId, Instant now, int limit) {
        return new ArrayList<>(findActiveForUser(userId, now, limit));
    }

    @Override
    public synchronized int purgeExpired(Instant now, int limit) {
        Instant point = now == null ? Instant.now() : now;
        int deleted = 0;
        for (String id : List.copyOf(entries.keySet())) {
            if (deleted >= Math.max(1, limit)) break;
            UserMemoryEntry value = entries.get(id);
            if (value != null && value.deletedAt() == null && !value.expiresAt().isAfter(point)) {
                entries.put(id, new UserMemoryEntry(value.memoryId(), value.userId(), value.conversationId(),
                        value.type(), value.contentDigest(), value.ciphertext(), value.keyVersion(), value.tenantId(),
                        value.sourceMessageId(), value.confidence(), value.consentId(), value.effectiveFrom(),
                        value.expiresAt(), point, value.createdAt(), point));
                deleted++;
            }
        }
        return deleted;
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
