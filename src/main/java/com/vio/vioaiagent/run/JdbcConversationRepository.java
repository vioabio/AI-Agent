package com.vio.vioaiagent.run;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.List;

/**
 * 生产环境会话仓储。用户删除采用软删除，保留 Run/答案投影/HITL 审计的
 * append-only 证据，避免外键级联触发不可变表的 DELETE 拒绝；查询统一过滤
 * {@code deleted_at IS NULL}，因此对原用户仍表现为资源已删除。
 */
@Repository
@Profile("prod")
@Primary
public class JdbcConversationRepository implements ConversationRepository {
    private final JdbcTemplate jdbc;
    private final RowMapper<Conversation> mapper = JdbcConversationRepository::map;

    public JdbcConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Conversation save(Conversation conversation) {
        if (conversation == null) throw new IllegalArgumentException("会话不能为空");
        jdbc.update("""
                INSERT INTO conversation (id, user_id, title, created_at, updated_at, deleted_at)
                VALUES (?, ?, ?, ?, ?, NULL)
                ON CONFLICT (id) DO UPDATE SET
                    title = EXCLUDED.title,
                    updated_at = EXCLUDED.updated_at,
                    deleted_at = NULL
                WHERE conversation.user_id = EXCLUDED.user_id
                  AND conversation.deleted_at IS NULL
                """, conversation.id(), conversation.userId(), conversation.title(),
                Timestamp.from(conversation.createdAt()), Timestamp.from(conversation.updatedAt()));
        return conversation;
    }

    @Override
    public Optional<Conversation> findById(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return Optional.empty();
        return queryOne("SELECT * FROM conversation WHERE id = ? AND deleted_at IS NULL", conversationId);
    }

    @Override
    public Optional<Conversation> findByIdForUser(String conversationId, String userId) {
        if (conversationId == null || conversationId.isBlank() || userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return queryOne("""
                SELECT * FROM conversation
                WHERE id = ? AND user_id = ? AND deleted_at IS NULL
                """, conversationId, userId);
    }

    @Override
    public List<Conversation> findForUser(String userId, int limit) {
        if (userId == null || userId.isBlank() || limit <= 0) return List.of();
        return jdbc.query("""
                SELECT * FROM conversation WHERE user_id = ? AND deleted_at IS NULL
                ORDER BY updated_at DESC, id ASC LIMIT ?
                """, mapper, userId.trim(), Math.min(limit, 201));
    }

    @Override
    public List<Conversation> findForUser(String userId, int limit, int offset) {
        if (userId == null || userId.isBlank() || limit <= 0 || offset < 0) return List.of();
        return jdbc.query("""
                SELECT * FROM conversation WHERE user_id = ? AND deleted_at IS NULL
                ORDER BY updated_at DESC, id ASC LIMIT ? OFFSET ?
                """, mapper, userId.trim(), Math.min(limit, 201), Math.min(offset, 1_000_000));
    }

    @Override
    public boolean updateTitleForUser(String conversationId, String userId, String title) {
        if (conversationId == null || userId == null || title == null || title.isBlank()) return false;
        return jdbc.update("""
                UPDATE conversation SET title = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND deleted_at IS NULL
                """, title.trim(), conversationId, userId.trim()) == 1;
    }

    @Override
    public void deleteForUser(String conversationId, String userId) {
        if (conversationId == null || userId == null) return;
        // 不做物理 DELETE：agent_run 的级联链会触发 V21 answer_projection 和
        // V17 audit_event 的 append-only 保护，且 V15 HITL approval 明确 RESTRICT。
        // 软删除后所有面向用户的查询仍因 deleted_at 过滤而返回 404/空列表。
        jdbc.update("""
                UPDATE conversation
                SET deleted_at = COALESCE(deleted_at, CURRENT_TIMESTAMP),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND deleted_at IS NULL
                """, conversationId, userId.trim());
    }

    private Optional<Conversation> queryOne(String sql, Object... args) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, mapper, args));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    private static Conversation map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        return new Conversation(rs.getString("id"), rs.getString("user_id"), rs.getString("title"),
                created == null ? Instant.now() : created.toInstant(),
                updated == null ? (created == null ? Instant.now() : created.toInstant()) : updated.toInstant());
    }
}
