package com.vio.vioaiagent.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** PostgreSQL answer projection；同一 answer/run 重放只允许得到同一不可变快照。 */
@Repository
@Profile("prod")
@Primary
public class JdbcAnswerProjectionRepository implements AnswerProjectionRepository {
    private static final TypeReference<List<Map<String, Object>>> CITATIONS = new TypeReference<>() { };
    private static final TypeReference<List<String>> LIMITATIONS = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcAnswerProjectionRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        if (jdbc == null || mapper == null) throw new IllegalArgumentException("answer projection 依赖不能为空");
        this.jdbc = jdbc;
        this.mapper = mapper.copy().findAndRegisterModules();
    }

    @Override
    @Transactional
    public AnswerProjection save(AnswerProjection value) {
        if (value == null) throw new IllegalArgumentException("Answer projection 不能为空");
        jdbc.update("""
                INSERT INTO answer_projection (
                    answer_id, run_id, session_id, conversation_id, user_id, trace_id,
                    status, answer_text, game_release_id, dlc_id, battle_format_id,
                    ruleset_version_id, data_release_id, knowledge_release_id,
                    index_release_id, contract_version, lineage_status, answer_digest,
                    citations, limitations, created_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                ON CONFLICT (answer_id) DO NOTHING
                """, value.answerId(), value.runId(), value.sessionId(), value.conversationId(), value.userId(),
                value.traceId(), value.status(), value.answer(), value.gameReleaseId(), value.dlcId(),
                value.battleFormatId(), value.rulesetVersionId(), value.dataReleaseId(), value.knowledgeReleaseId(),
                value.indexReleaseId(), value.contractVersion(), value.lineageStatus().name(), value.answerDigest(),
                json(value.citations()), json(value.limitations()), timestamp(value.createdAt()),
                timestamp(value.completedAt()));

        Optional<AnswerProjection> existing = findById(value.answerId());
        if (existing.isPresent()) {
            if (!existing.get().equals(value)) {
                throw new IllegalStateException("answerId 已存在且投影内容不一致: " + value.answerId());
            }
            return existing.get();
        }
        Optional<AnswerProjection> byRun = findByRunId(value.runId());
        if (byRun.isPresent()) {
            if (!byRun.get().equals(value)) {
                throw new RunConflictException(value.runId(), 0L);
            }
            return byRun.get();
        }
        throw new IllegalStateException("Answer projection 写入后无法读取: " + value.answerId());
    }

    @Override
    public Optional<AnswerProjection> findById(String answerId) {
        if (answerId == null || answerId.isBlank()) return Optional.empty();
        return query("answer_id = ?", answerId.trim());
    }

    @Override
    public Optional<AnswerProjection> findByIdForUser(String answerId, String userId) {
        if (answerId == null || answerId.isBlank() || userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return query("answer_id = ? AND user_id = ?", answerId.trim(), userId.trim());
    }

    @Override
    public Optional<AnswerProjection> findByRunId(String runId) {
        if (runId == null || runId.isBlank()) return Optional.empty();
        return query("run_id = ?", runId.trim());
    }

    private Optional<AnswerProjection> query(String predicate, Object... args) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("SELECT * FROM answer_projection WHERE " + predicate,
                    this::map, args));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    private AnswerProjection map(ResultSet rs, int rowNum) throws SQLException {
        AnswerProjection.LineageStatus lineage;
        try {
            lineage = AnswerProjection.LineageStatus.valueOf(rs.getString("lineage_status"));
        } catch (RuntimeException ex) {
            throw new SQLException("未知 answer lineage status", ex);
        }
        return new AnswerProjection(
                rs.getString("answer_id"), rs.getString("run_id"), rs.getString("session_id"),
                rs.getString("conversation_id"), rs.getString("user_id"), rs.getString("trace_id"),
                rs.getString("status"), rs.getString("answer_text"), rs.getString("game_release_id"),
                rs.getString("dlc_id"), rs.getString("battle_format_id"), rs.getString("ruleset_version_id"),
                rs.getString("data_release_id"), rs.getString("knowledge_release_id"),
                rs.getString("index_release_id"), rs.getString("contract_version"), lineage,
                rs.getString("answer_digest"), readCitations(rs.getString("citations")),
                readLimitations(rs.getString("limitations")), instant(rs, "created_at"),
                instant(rs, "completed_at"));
    }

    private List<Map<String, Object>> readCitations(String value) throws SQLException {
        if (value == null || value.isBlank()) return List.of();
        try { return mapper.readValue(value, CITATIONS); }
        catch (JsonProcessingException ex) { throw new SQLException("answer citations JSON 损坏", ex); }
    }

    private List<String> readLimitations(String value) throws SQLException {
        if (value == null || value.isBlank()) return List.of();
        try { return mapper.readValue(value, LIMITATIONS); }
        catch (JsonProcessingException ex) { throw new SQLException("answer limitations JSON 损坏", ex); }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value == null ? List.of() : value); }
        catch (JsonProcessingException ex) { throw new IllegalArgumentException("answer projection JSON 无法序列化", ex); }
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
