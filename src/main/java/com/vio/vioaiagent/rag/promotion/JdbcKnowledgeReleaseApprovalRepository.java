package com.vio.vioaiagent.rag.promotion;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** PostgreSQL append-only approval adapter; V23 trigger rejects UPDATE/DELETE. */
@Repository
@Primary
@Profile({"prod", "demo"})
public class JdbcKnowledgeReleaseApprovalRepository implements KnowledgeReleaseApprovalRepository {

    private static final String COLUMNS = "approval_id, knowledge_release_id, index_release_id, "
            + "data_release_id, evaluation_run_id, report_hash, reviewer, reason, approved_at, gate_version";

    private final JdbcTemplate jdbc;

    public JdbcKnowledgeReleaseApprovalRepository(JdbcTemplate jdbc) {
        if (jdbc == null) throw new IllegalArgumentException("jdbc 不能为空");
        this.jdbc = jdbc;
    }

    @Override
    public boolean durable() {
        return true;
    }

    @Override
    @Transactional
    public ReservationResult createOrGet(KnowledgeReleaseApproval candidate) {
        if (candidate == null) throw new IllegalArgumentException("candidate 不能为空");
        Optional<KnowledgeReleaseApproval> existing = findByNaturalKey(candidate.knowledgeReleaseId(),
                candidate.indexReleaseId(), candidate.evaluationRunId(), candidate.reportHash());
        if (existing.isPresent()) {
            if (!same(existing.get(), candidate)) {
                throw new IllegalStateException("审批自然键已存在且内容不可覆盖");
            }
            return new ReservationResult(existing.get(), false);
        }
        int inserted = jdbc.update("""
                INSERT INTO knowledge_release_approval
                    (approval_id, knowledge_release_id, index_release_id, data_release_id,
                     evaluation_run_id, report_hash, reviewer, reason, approved_at, gate_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (knowledge_release_id, index_release_id, evaluation_run_id, report_hash)
                DO NOTHING
                """, candidate.approvalId(), candidate.knowledgeReleaseId(), candidate.indexReleaseId(),
                candidate.dataReleaseId(), candidate.evaluationRunId(), candidate.reportHash(),
                candidate.reviewer(), candidate.reason(), timestamp(candidate.approvedAt()), candidate.gateVersion());
        Optional<KnowledgeReleaseApproval> persisted = findByNaturalKey(candidate.knowledgeReleaseId(),
                candidate.indexReleaseId(), candidate.evaluationRunId(), candidate.reportHash());
        if (persisted.isEmpty()) {
            throw new IllegalStateException("审批写入后无法回读: " + candidate.approvalId());
        }
        if (!same(persisted.get(), candidate)) {
            throw new IllegalStateException("审批写入后回读内容不一致");
        }
        return new ReservationResult(persisted.get(), inserted == 1
                && persisted.get().approvalId().equals(candidate.approvalId()));
    }

    @Override
    public Optional<KnowledgeReleaseApproval> findByApprovalId(String approvalId) {
        if (approvalId == null || approvalId.isBlank()) return Optional.empty();
        return jdbc.query("SELECT " + COLUMNS + " FROM knowledge_release_approval WHERE approval_id = ?",
                this::map, approvalId.trim()).stream().findFirst();
    }

    @Override
    public Optional<KnowledgeReleaseApproval> findByNaturalKey(String knowledgeReleaseId,
                                                                String indexReleaseId,
                                                                String evaluationRunId,
                                                                String reportHash) {
        if (blank(knowledgeReleaseId) || blank(indexReleaseId) || blank(evaluationRunId)
                || blank(reportHash)) return Optional.empty();
        return jdbc.query("""
                SELECT %s FROM knowledge_release_approval
                WHERE knowledge_release_id = ? AND index_release_id = ?
                  AND evaluation_run_id = ? AND report_hash = ?
                LIMIT 1
                """.formatted(COLUMNS), this::map, knowledgeReleaseId.trim(), indexReleaseId.trim(),
                evaluationRunId.trim(), reportHash.trim()).stream().findFirst();
    }

    private KnowledgeReleaseApproval map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new KnowledgeReleaseApproval(rs.getString("approval_id"),
                rs.getString("knowledge_release_id"), rs.getString("index_release_id"),
                rs.getString("data_release_id"), rs.getString("evaluation_run_id"),
                rs.getString("report_hash"), rs.getString("reviewer"), rs.getString("reason"),
                instant(rs.getObject("approved_at")), rs.getString("gate_version"));
    }

    private boolean same(KnowledgeReleaseApproval left, KnowledgeReleaseApproval right) {
        return left.approvalId().equals(right.approvalId())
                && left.knowledgeReleaseId().equals(right.knowledgeReleaseId())
                && left.indexReleaseId().equals(right.indexReleaseId())
                && left.dataReleaseId().equals(right.dataReleaseId())
                && left.evaluationRunId().equals(right.evaluationRunId())
                && left.reportHash().equals(right.reportHash())
                && left.reviewer().equals(right.reviewer())
                && java.util.Objects.equals(left.reason(), right.reason())
                && left.gateVersion().equals(right.gateVersion());
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.time.OffsetDateTime offset) return offset.toInstant();
        if (value instanceof java.time.Instant instant) return instant;
        return Instant.parse(value.toString());
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
