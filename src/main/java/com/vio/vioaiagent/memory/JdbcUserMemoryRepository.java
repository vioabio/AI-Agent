package com.vio.vioaiagent.memory;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** PostgreSQL 用户记忆仓储；查询始终带 user_id，避免跨主体召回。 */
@Repository
@Primary
@Profile({"prod", "demo"})
public class JdbcUserMemoryRepository implements UserMemoryRepository {
    private final JdbcTemplate jdbc;
    public JdbcUserMemoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public UserMemoryEntry save(UserMemoryEntry entry) {
        if (entry == null) throw new IllegalArgumentException("记忆不能为空");
        jdbc.update("""
                INSERT INTO memory_entry
                    (memory_id,user_id,conversation_id,type,content_digest,ciphertext,key_version,tenant_id,
                     source_message_id,confidence,consent_id,effective_from,expires_at,deleted_at,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (memory_id) DO UPDATE SET
                    ciphertext=EXCLUDED.ciphertext, content_digest=EXCLUDED.content_digest,
                    expires_at=EXCLUDED.expires_at, deleted_at=EXCLUDED.deleted_at, updated_at=EXCLUDED.updated_at
                """, entry.memoryId(), entry.userId(), entry.conversationId(), entry.type(), entry.contentDigest(),
                entry.ciphertext(), entry.keyVersion(), entry.tenantId(), entry.sourceMessageId(), entry.confidence(),
                entry.consentId(), ts(entry.effectiveFrom()), ts(entry.expiresAt()), ts(entry.deletedAt()),
                ts(entry.createdAt()), ts(entry.updatedAt()));
        return findByIdForUser(entry.memoryId(), entry.userId()).orElseThrow();
    }

    @Override
    public Optional<UserMemoryEntry> findByIdForUser(String memoryId, String userId) {
        if (blank(memoryId) || blank(userId)) return Optional.empty();
        try { return Optional.ofNullable(jdbc.queryForObject("SELECT * FROM memory_entry WHERE memory_id=? AND user_id=?", this::map, memoryId.trim(), userId.trim())); }
        catch (EmptyResultDataAccessException ex) { return Optional.empty(); }
    }

    @Override
    public List<UserMemoryEntry> findActiveForUser(String userId, Instant now, int limit) {
        if (blank(userId) || limit <= 0) return List.of();
        Instant point = now == null ? Instant.now() : now;
        return jdbc.query("""
                SELECT * FROM memory_entry
                 WHERE user_id=? AND deleted_at IS NULL AND effective_from <= ? AND expires_at > ?
                 ORDER BY created_at DESC, memory_id DESC LIMIT ?
                """, this::map, userId.trim(), ts(point), ts(point), Math.min(limit, 500));
    }

    @Override
    public boolean deleteForUser(String memoryId, String userId, Instant deletedAt) {
        if (blank(memoryId) || blank(userId)) return false;
        return jdbc.update("UPDATE memory_entry SET deleted_at=?,updated_at=? WHERE memory_id=? AND user_id=? AND deleted_at IS NULL",
                ts(deletedAt == null ? Instant.now() : deletedAt), ts(Instant.now()), memoryId.trim(), userId.trim()) == 1;
    }

    @Override
    public List<UserMemoryEntry> exportForUser(String userId, Instant now, int limit) {
        return findActiveForUser(userId, now, limit);
    }

    @Override
    public int purgeExpired(Instant now, int limit) {
        Instant point = now == null ? Instant.now() : now;
        int bounded = Math.max(1, Math.min(limit, 10_000));
        return jdbc.update("""
                UPDATE memory_entry SET deleted_at=?,updated_at=?
                 WHERE ctid IN (SELECT ctid FROM memory_entry WHERE deleted_at IS NULL AND expires_at <= ? LIMIT ?)
                """, ts(point), ts(point), ts(point), bounded);
    }

    private UserMemoryEntry map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new UserMemoryEntry(rs.getString("memory_id"), rs.getString("user_id"), rs.getString("conversation_id"),
                rs.getString("type"), rs.getString("content_digest"), rs.getString("ciphertext"), rs.getString("key_version"),
                rs.getString("tenant_id"), rs.getString("source_message_id"), rs.getDouble("confidence"), rs.getString("consent_id"),
                instant(rs,"effective_from"), instant(rs,"expires_at"), instant(rs,"deleted_at"), instant(rs,"created_at"), instant(rs,"updated_at"));
    }
    private Instant instant(java.sql.ResultSet rs, String name) throws java.sql.SQLException { Timestamp value=rs.getTimestamp(name); return value==null?null:value.toInstant(); }
    private Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
}
