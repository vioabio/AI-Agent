package com.vio.vioaiagent.run;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Local chat history only. Runs/events/leases retain their existing lifecycle stores. */
@Repository
@Profile("!prod & !demo")
@ConditionalOnProperty(name = "vio.local-history.enabled", havingValue = "true")
public class SqliteLocalHistoryRepository implements ConversationRepository, ChatMessageRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SqliteLocalHistoryRepository(
            @Value("${vio.local-history.path:${user.dir}/.local-data/history.sqlite}") String file,
            ObjectMapper mapper) {
        this.mapper = mapper.copy();
        try {
            Path path = Path.of(file).toAbsolutePath().normalize();
            Files.createDirectories(path.getParent());
            SQLiteConfig config = new SQLiteConfig();
            config.enforceForeignKeys(true);
            config.setBusyTimeout(5000);
            config.setJournalMode(SQLiteConfig.JournalMode.WAL);
            SQLiteDataSource dataSource = new SQLiteDataSource(config);
            dataSource.setUrl("jdbc:sqlite:" + path);
            jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS local_conversation (
                      id TEXT PRIMARY KEY, user_id TEXT NOT NULL, title TEXT,
                      created_at TEXT NOT NULL, updated_at TEXT NOT NULL)
                    """);
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS local_message (
                      id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL REFERENCES local_conversation(id) ON DELETE CASCADE,
                      run_id TEXT, user_id TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL,
                      created_at TEXT NOT NULL, metadata TEXT NOT NULL)
                    """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_local_message_owner ON local_message(conversation_id,user_id)");
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS local_conversation_constraints (
                      conversation_id TEXT PRIMARY KEY REFERENCES local_conversation(id) ON DELETE CASCADE,
                      revision INTEGER NOT NULL CHECK (revision > 0), settings_json TEXT NOT NULL)
                    """);
        } catch (Exception e) {
            throw new IllegalStateException("本地会话数据库无法初始化，请检查 vio.local-history.path 和目录权限", e);
        }
    }

    @Override
    public synchronized Conversation save(Conversation c) {
        int written = jdbc.update("""
                INSERT INTO local_conversation(id,user_id,title,created_at,updated_at) VALUES(?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET title=excluded.title,updated_at=excluded.updated_at
                WHERE local_conversation.user_id=excluded.user_id
                """, c.id(), c.userId(), c.title(), c.createdAt().toString(), c.updatedAt().toString());
        if (written != 1) throw new IllegalStateException("会话归属冲突");
        return findByIdForUser(c.id(), c.userId()).orElseThrow();
    }

    @Override
    public synchronized Optional<ConversationConstraints> constraintsForUser(String id, String userId) {
        return new JdbcConversationConstraints(jdbc, true).read(id, userId);
    }
    @Override
    public synchronized boolean replaceConstraintsForUser(String id, String userId, ConversationConstraints expected) {
        return new JdbcConversationConstraints(jdbc, true).replace(id, userId, expected);
    }

    @Override
    public synchronized Optional<Conversation> findById(String id) {
        if (id == null) return Optional.empty();
        return jdbc.query("SELECT * FROM local_conversation WHERE id=?", (rs, n) ->
                new Conversation(rs.getString("id"), rs.getString("user_id"), rs.getString("title"),
                        Instant.parse(rs.getString("created_at")), Instant.parse(rs.getString("updated_at"))), id)
                .stream().findFirst();
    }

    @Override
    public synchronized Optional<Conversation> findByIdForUser(String id, String user) {
        return findById(id).filter(c -> c.userId().equals(user));
    }

    @Override
    public List<Conversation> findForUser(String user, int limit) { return findForUser(user, limit, 0); }

    @Override
    public synchronized List<Conversation> findForUser(String user, int limit, int offset) {
        if (user == null || limit <= 0 || offset < 0) return List.of();
        return jdbc.query("SELECT * FROM local_conversation WHERE user_id=?", (rs, n) ->
                new Conversation(rs.getString("id"), rs.getString("user_id"), rs.getString("title"),
                        Instant.parse(rs.getString("created_at")), Instant.parse(rs.getString("updated_at"))), user)
                .stream().sorted(Comparator.comparing(Conversation::updatedAt).reversed().thenComparing(Conversation::id))
                .skip(offset).limit(Math.min(201, limit)).toList();
    }

    @Override
    public synchronized boolean updateTitleForUser(String id, String user, String title) {
        if (title == null || title.isBlank()) return false;
        return jdbc.update("UPDATE local_conversation SET title=?,updated_at=? WHERE id=? AND user_id=?",
                title.trim(), Instant.now().toString(), id, user) == 1;
    }

    @Override
    public synchronized void deleteForUser(String id, String user) {
        jdbc.update("DELETE FROM local_conversation WHERE id=? AND user_id=?", id, user);
    }

    @Override
    public synchronized ChatMessage save(ChatMessage message) {
        if (findByIdForUser(message.conversationId(), message.userId()).isEmpty()) {
            throw new IllegalStateException("消息不属于有效会话");
        }
        try {
            int written = jdbc.update("""
                    INSERT INTO local_message(id,conversation_id,run_id,user_id,role,content,created_at,metadata)
                    VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING
                    """, message.id(), message.conversationId(), message.runId(), message.userId(),
                    message.role(), message.content(), message.createdAt().toString(), mapper.writeValueAsString(message.metadata()));
            if (written == 0) {
                ChatMessage original = findByConversationForUser(message.conversationId(), message.userId()).stream()
                        .filter(m -> m.id().equals(message.id())).findFirst().orElseThrow();
                if (!original.equals(message)) throw new IllegalStateException("本地消息 ID 已绑定其他内容");
                return original;
            }
            return message;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("无法保存本地消息元数据", e);
        }
    }

    @Override
    public synchronized List<ChatMessage> findByConversationForUser(String id, String user) {
        if (findByIdForUser(id, user).isEmpty()) return List.of();
        return jdbc.query("SELECT * FROM local_message WHERE conversation_id=? AND user_id=?", (rs, n) -> {
            Map<String, Object> metadata;
            try { metadata = mapper.readValue(rs.getString("metadata"), new TypeReference<>() { }); }
            catch (Exception e) { throw new IllegalStateException("本地消息元数据损坏", e); }
            return new ChatMessage(rs.getString("id"), id, rs.getString("run_id"), user,
                    rs.getString("role"), rs.getString("content"), Instant.parse(rs.getString("created_at")), metadata);
        }, id, user).stream().sorted(Comparator.comparing(ChatMessage::createdAt).thenComparing(ChatMessage::id)).toList();
    }

    @Override
    public synchronized void deleteByConversation(String id) {
        jdbc.update("DELETE FROM local_message WHERE conversation_id=?", id);
    }
}
