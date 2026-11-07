package com.vio.vioaiagent.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** 用户级记忆用例：授权、TTL、加密和主体隔离集中在此处。 */
public class UserMemoryService {
    private final UserMemoryRepository repository;
    private final MemoryCipher cipher;
    private final int defaultTtlDays;

    public UserMemoryService(UserMemoryRepository repository, MemoryCipher cipher, int defaultTtlDays) {
        if (repository == null || cipher == null) throw new IllegalArgumentException("记忆依赖不能为空");
        this.repository = repository;
        this.cipher = cipher;
        this.defaultTtlDays = Math.max(1, Math.min(defaultTtlDays, 3650));
    }

    public UserMemoryEntry remember(String userId, String conversationId, String type, String content,
                                    double confidence, String consentId, Duration ttl) {
        requireUser(userId);
        String subject = userId.trim();
        if (consentId == null || consentId.isBlank()) throw new IllegalArgumentException("未提供记忆授权");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("记忆内容不能为空");
        Duration effectiveTtl = ttl == null ? Duration.ofDays(defaultTtlDays) : ttl;
        if (effectiveTtl.isZero() || effectiveTtl.isNegative() || effectiveTtl.compareTo(Duration.ofDays(3650)) > 0) {
            throw new IllegalArgumentException("记忆 TTL 无效");
        }
        Instant now = Instant.now();
        MemoryCipher.Encrypted encrypted = cipher.encrypt(subject, content);
        UserMemoryEntry entry = new UserMemoryEntry(UUID.randomUUID().toString(), subject, clean(conversationId),
                normalizeType(type), encrypted.contentDigest(), encrypted.ciphertext(), encrypted.keyVersion(),
                null, null, confidence, consentId.trim(), now, now.plus(effectiveTtl), null, now, now);
        return repository.save(entry);
    }

    public List<MemoryView> recall(String userId, String query, int limit) {
        requireUser(userId);
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return repository.findActiveForUser(userId.trim(), Instant.now(), Math.max(1, Math.min(limit, 100)))
                .stream().map(entry -> toView(entry, needle)).filter(view -> needle.isBlank() || view.content().toLowerCase(Locale.ROOT).contains(needle)).toList();
    }

    public Optional<MemoryView> find(String userId, String memoryId) {
        requireUser(userId);
        return repository.findByIdForUser(memoryId, userId).filter(value -> value.activeAt(Instant.now())).map(value -> toView(value, ""));
    }

    public boolean delete(String userId, String memoryId) {
        requireUser(userId);
        return repository.deleteForUser(memoryId, userId, Instant.now());
    }

    public List<MemoryExport> export(String userId, int limit) {
        requireUser(userId);
        return repository.exportForUser(userId, Instant.now(), Math.max(1, Math.min(limit, 500))).stream()
                .map(entry -> new MemoryExport(entry.memoryId(), entry.type(), entry.contentDigest(), entry.keyVersion(),
                        entry.consentId(), entry.effectiveFrom(), entry.expiresAt())).toList();
    }

    public int purgeExpired(int limit) { return repository.purgeExpired(Instant.now(), limit); }

    private MemoryView toView(UserMemoryEntry entry, String ignored) {
        String content = cipher.decrypt(entry.userId(), entry.ciphertext(), entry.keyVersion());
        return new MemoryView(entry.memoryId(), entry.userId(), entry.conversationId(), entry.type(), content,
                entry.confidence(), entry.effectiveFrom(), entry.expiresAt());
    }
    private void requireUser(String userId) { if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId 不能为空"); }
    private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String normalizeType(String value) { return value == null || value.isBlank() ? "FACT" : value.trim().toUpperCase(Locale.ROOT); }

    public record MemoryView(String memoryId, String userId, String conversationId, String type, String content,
                             double confidence, Instant effectiveFrom, Instant expiresAt) { }
    public record MemoryExport(String memoryId, String type, String contentDigest, String keyVersion,
                               String consentId, Instant effectiveFrom, Instant expiresAt) { }
}
