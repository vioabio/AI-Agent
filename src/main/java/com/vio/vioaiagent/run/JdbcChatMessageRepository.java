package com.vio.vioaiagent.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 生产环境会话消息仓储；消息 ID 作为幂等键，重试不会重复写入。 */
@Repository
@Profile("prod")
@Primary
public class JdbcChatMessageRepository implements ChatMessageRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<ChatMessage> mapper = JdbcChatMessageRepository::map;

    public JdbcChatMessageRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
    }

    @Override
    public ChatMessage save(ChatMessage message) {
        if (message == null) throw new IllegalArgumentException("消息不能为空");
        jdbc.update("""
                INSERT INTO chat_message (
                    id, conversation_id, run_id, user_id, role, content, created_at, metadata
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (id) DO NOTHING
                """, message.id(), message.conversationId(), message.runId(), message.userId(), message.role(),
                message.content(), Timestamp.from(message.createdAt()), toJson(message.metadata()));
        return message;
    }

    @Override
    public List<ChatMessage> findByConversationForUser(String conversationId, String userId) {
        if (conversationId == null || userId == null) return List.of();
        return jdbc.query("""
                SELECT m.* FROM chat_message m
                JOIN conversation c ON c.id = m.conversation_id
                WHERE m.conversation_id = ? AND m.user_id = ? AND c.user_id = ? AND c.deleted_at IS NULL
                ORDER BY m.created_at ASC, m.id ASC
                """, mapper, conversationId, userId, userId);
    }

    @Override
    public void deleteByConversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return;
        jdbc.update("DELETE FROM chat_message WHERE conversation_id = ?", conversationId);
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("消息 metadata 无法序列化", ex);
        }
    }

    private static ChatMessage map(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> metadata;
        try {
            String json = rs.getString("metadata");
            metadata = json == null || json.isBlank() ? Map.of() : new ObjectMapper().readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            throw new SQLException("消息 metadata 无法解析", ex);
        }
        Timestamp created = rs.getTimestamp("created_at");
        return new ChatMessage(rs.getString("id"), rs.getString("conversation_id"), rs.getString("run_id"),
                rs.getString("user_id"), rs.getString("role"), rs.getString("content"),
                created == null ? Instant.now() : created.toInstant(), metadata);
    }
}
