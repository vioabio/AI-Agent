package com.vio.vioaiagent.feedback;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** PostgreSQL 生产反馈仓储；工单快照和迁移审计记录在同一事务中保存。 */
@Repository
@Primary
@Profile("prod")
public class JdbcFeedbackRepository implements FeedbackRepository {
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcFeedbackRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = require(jdbc, "jdbc");
        this.objectMapper = require(objectMapper, "objectMapper").copy().findAndRegisterModules();
    }

    @Override
    public CorrectionCase save(CorrectionCase value) {
        if (value == null) throw new IllegalArgumentException("反馈工单不能为空");
        // 反馈事实是提交时的不可变快照。只 INSERT、不在冲突时覆盖，
        // 避免重试或伪造 feedbackId 把其他用户的证据/版本改写。
        jdbc.update("""
                INSERT INTO user_feedback (
                    feedback_id, answer_id, trace_id, error_type, evidence, user_id,
                    data_release_id, knowledge_release_id, index_release_id, feedback_type,
                    priority, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (feedback_id) DO NOTHING
                """, value.feedbackId(), value.answerId(), value.traceId(), value.errorType(),
                json(value.evidence()), value.userId(), value.dataReleaseId(), value.knowledgeReleaseId(),
                value.indexReleaseId(), value.feedbackType().name(), value.priority().name(),
                timestamp(value.createdAt()), timestamp(value.updatedAt()));

        Optional<String> owner = ownerOf(value.feedbackId());
        if (owner.isPresent() && !owner.get().equals(value.userId())) {
            throw new FeedbackConflictException("feedbackId 已被其他主体占用");
        }
        if (owner.isEmpty()) {
            throw new FeedbackConflictException("反馈事实写入失败，记录不存在");
        }

        int caseRows = jdbc.update("""
                INSERT INTO correction_case (
                    feedback_id, status, assigned_to, fix_release_id, evaluation_result,
                    version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (feedback_id) DO UPDATE SET
                    status = EXCLUDED.status,
                    assigned_to = EXCLUDED.assigned_to,
                    fix_release_id = EXCLUDED.fix_release_id,
                    evaluation_result = EXCLUDED.evaluation_result,
                    version = EXCLUDED.version,
                    updated_at = EXCLUDED.updated_at
                WHERE correction_case.version < EXCLUDED.version
                """, value.feedbackId(), value.status().name(), value.assignedTo(), value.fixReleaseId(),
                value.evaluationResult(), value.version(), timestamp(value.createdAt()), timestamp(value.updatedAt()));

        if (caseRows == 0) {
            // 同一快照重试是幂等成功；其余情况说明客户端拿着旧版本写入。
            CorrectionCase existing = findById(value.feedbackId()).orElse(null);
            if (existing != null && value.version() == 0
                    && existing.userId().equals(value.userId())) return existing;
            if (existing != null && existing.equals(value)) return existing;
            throw new FeedbackConflictException("反馈版本冲突，拒绝覆盖更新");
        }

        // 状态迁移需要刷新列表排序时间，但不改变提交时的事实字段。
        jdbc.update("UPDATE user_feedback SET updated_at = ? WHERE feedback_id = ?",
                timestamp(value.updatedAt()), value.feedbackId());

        for (int i = 0; i < value.history().size(); i++) {
            FeedbackTransition transition = value.history().get(i);
            jdbc.update("""
                    INSERT INTO feedback_transition (
                        feedback_id, transition_no, from_status, to_status, actor_id,
                        actor_role, reason, trace_id, changed_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (feedback_id, transition_no) DO NOTHING
                    """, value.feedbackId(), i, nullable(transition.fromStatus()), transition.toStatus().name(),
                    transition.actorId(), transition.role().name(), transition.reason(), transition.traceId(),
                    timestamp(transition.changedAt()));
        }
        return value;
    }

    private Optional<String> ownerOf(String feedbackId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT user_id FROM user_feedback WHERE feedback_id = ?", String.class, feedbackId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<CorrectionCase> findById(String feedbackId) {
        if (feedbackId == null || feedbackId.isBlank()) return Optional.empty();
        try {
            return Optional.ofNullable(jdbc.queryForObject(baseSql("f.feedback_id = ?"), this::mapCase,
                    feedbackId.trim()));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<CorrectionCase> findByIdForUser(String feedbackId, String userId) {
        if (feedbackId == null || feedbackId.isBlank() || userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(jdbc.queryForObject(baseSql("f.feedback_id = ? AND f.user_id = ?"),
                    this::mapCase, feedbackId.trim(), userId.trim()));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public List<CorrectionCase> findForUser(String userId, int limit) {
        if (userId == null || userId.isBlank() || limit <= 0) return List.of();
        return jdbc.query(baseSql("f.user_id = ? ORDER BY f.updated_at DESC, f.feedback_id LIMIT ?"),
                this::mapCase, userId.trim(), Math.min(limit, 200));
    }

    @Override
    public List<CorrectionCase> findForUserAndAnswer(String userId, String answerId, int limit) {
        if (userId == null || userId.isBlank() || answerId == null || answerId.isBlank() || limit <= 0) {
            return List.of();
        }
        // answerId 条件必须在 SQL 层生效，再 LIMIT；否则某用户反馈很多时，
        // Controller 先分页后过滤会返回空页，即使后续页面存在匹配记录。
        return jdbc.query(baseSql("f.user_id = ? AND f.answer_id = ? "
                        + "ORDER BY f.updated_at DESC, f.feedback_id LIMIT ?"),
                this::mapCase, userId.trim(), answerId.trim(), Math.min(limit, 200));
    }

    @Override
    public List<CorrectionCase> findAll(FeedbackState status, int limit) {
        if (limit <= 0) return List.of();
        if (status == null) {
            return jdbc.query(baseSql("TRUE ORDER BY f.updated_at DESC, f.feedback_id LIMIT ?"),
                    this::mapCase, Math.min(limit, 200));
        }
        return jdbc.query(baseSql("f.status = ? ORDER BY f.updated_at DESC, f.feedback_id LIMIT ?"),
                this::mapCase, status.name(), Math.min(limit, 200));
    }

    private String baseSql(String predicate) {
        return """
                SELECT f.feedback_id, f.answer_id, f.trace_id, f.error_type, f.evidence, f.user_id,
                       f.data_release_id, f.knowledge_release_id, f.index_release_id, f.feedback_type,
                       f.priority, f.created_at, f.updated_at,
                       c.status, c.assigned_to, c.fix_release_id, c.evaluation_result,
                       c.version, c.created_at AS case_created_at, c.updated_at AS case_updated_at
                FROM user_feedback f
                JOIN correction_case c ON c.feedback_id = f.feedback_id
                WHERE """ + predicate;
    }

    private CorrectionCase mapCase(ResultSet rs, int rowNum) throws SQLException {
        List<FeedbackTransition> history = jdbc.query("""
                        SELECT from_status, to_status, actor_id, actor_role, reason, trace_id, changed_at
                        FROM feedback_transition WHERE feedback_id = ? ORDER BY transition_no
                        """, this::mapTransition, rs.getString("feedback_id"));
        return new CorrectionCase(rs.getString("feedback_id"), rs.getString("answer_id"), rs.getString("trace_id"),
                rs.getString("error_type"), readJson(rs.getString("evidence")), rs.getString("user_id"),
                rs.getString("data_release_id"), rs.getString("knowledge_release_id"), rs.getString("index_release_id"),
                FeedbackType.parse(rs.getString("feedback_type")), FeedbackPriority.parse(rs.getString("priority")),
                FeedbackState.parse(rs.getString("status")), rs.getString("assigned_to"), rs.getString("fix_release_id"),
                rs.getString("evaluation_result"), instant(rs.getObject("created_at")),
                instant(rs.getObject("updated_at")), rs.getLong("version"), history);
    }

    private FeedbackTransition mapTransition(ResultSet rs, int rowNum) throws SQLException {
        String from = rs.getString("from_status");
        return new FeedbackTransition(from == null ? null : FeedbackState.parse(from),
                FeedbackState.parse(rs.getString("to_status")), rs.getString("actor_id"),
                FeedbackRole.parse(rs.getString("actor_role")), rs.getString("reason"),
                rs.getString("trace_id"), instant(rs.getObject("changed_at")));
    }

    private Map<String, Object> readJson(String value) throws SQLException {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(value, JSON_MAP);
        } catch (JsonProcessingException ex) {
            throw new SQLException("反馈证据 JSON 损坏", ex);
        }
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("反馈证据无法序列化", ex);
        }
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant instant(Object value) throws SQLException {
        if (value == null) return null;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.time.OffsetDateTime offsetDateTime) return offsetDateTime.toInstant();
        if (value instanceof java.time.LocalDateTime localDateTime) {
            return localDateTime.toInstant(java.time.ZoneOffset.UTC);
        }
        if (value instanceof Instant instant) return instant;
        try {
            return Instant.parse(value.toString());
        } catch (RuntimeException ex) {
            throw new SQLException("反馈时间字段非法", ex);
        }
    }

    private String nullable(FeedbackState value) {
        return value == null ? null : value.name();
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " 不能为空");
        return value;
    }
}
