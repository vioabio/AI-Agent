package com.vio.vioaiagent.rag.promotion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/** Exact producer bytes are append-only; every read verifies the stored digest and frozen rules. */
@Repository
@Primary
@Profile({"prod", "demo"})
public class JdbcEvaluationReportRepository implements EvaluationReportRepository {
    private static final int MAX_BASELINE_DEPTH = 8;
    private final JdbcTemplate jdbc;
    private final EvaluationReportArtifactDecoder decoder;

    public JdbcEvaluationReportRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc 不能为空");
        this.decoder = new EvaluationReportArtifactDecoder(mapper);
    }

    @Override
    public Optional<EvaluationReport> findByEvaluationRunId(String evaluationRunId) {
        if (evaluationRunId == null || evaluationRunId.isBlank()) return Optional.empty();
        return findVerified(evaluationRunId.trim(), new LinkedHashSet<>());
    }

    @Override
    @Transactional
    public EvaluationReport importArtifact(byte[] artifact, String expectedHash,
                                           String source, String importedBy) {
        String origin = metadata(source, "source", 512);
        String actor = metadata(importedBy, "importedBy", 256);
        if (artifact == null) throw new IllegalArgumentException("artifact 不能为空");
        // Copy the caller's buffer before hashing, parsing and persistence.
        byte[] snapshot = artifact.clone();
        EvaluationReport report = decodeVerified(snapshot, expectedHash, new LinkedHashSet<>());
        jdbc.update("""
                INSERT INTO evaluation_report
                    (evaluation_run_id, report_hash, artifact_bytes, source, imported_by)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (evaluation_run_id) DO NOTHING
                """, report.evaluationRunId(), report.reportHash(), snapshot, origin, actor);
        StoredArtifact stored = findStored(report.evaluationRunId())
                .orElseThrow(() -> new IllegalStateException("评测报告写入后无法回读"));
        if (!stored.hash().equals(report.reportHash()) || !Arrays.equals(snapshot, stored.bytes())) {
            throw new EvaluationReportConflictException();
        }
        return findByEvaluationRunId(report.evaluationRunId())
                .orElseThrow(() -> new IllegalStateException("评测报告写入后无法校验"));
    }

    private Optional<EvaluationReport> findVerified(String id, Set<String> ancestry) {
        if (ancestry.size() >= MAX_BASELINE_DEPTH || !ancestry.add(id)) {
            throw new IllegalStateException("评测报告基线存在循环或超过深度限制");
        }
        try {
            Optional<StoredArtifact> found = findStored(id);
            if (found.isEmpty()) return Optional.empty();
            StoredArtifact stored = found.get();
            EvaluationReport report = decodeVerified(stored.bytes(), stored.hash(), ancestry);
            if (!id.equals(report.evaluationRunId())) {
                throw new IllegalStateException("评测报告存储键与原始内容不一致");
            }
            return Optional.of(report);
        } finally {
            ancestry.remove(id);
        }
    }

    private EvaluationReport decodeVerified(byte[] bytes, String hash, Set<String> ancestry) {
        String baselineId = EvaluationReportArtifactDecoder.baselineEvaluationRunId(bytes);
        EvaluationReport baseline = baselineId == null ? null : findVerified(baselineId, ancestry)
                .orElseThrow(() -> new IllegalArgumentException("找不到指定的不可变评测基线"));
        return decoder.decode(bytes, hash, baseline);
    }

    private Optional<StoredArtifact> findStored(String id) {
        return jdbc.query("""
                SELECT report_hash, artifact_bytes FROM evaluation_report WHERE evaluation_run_id = ?
                """, (rs, row) -> new StoredArtifact(rs.getString("report_hash"),
                rs.getBytes("artifact_bytes")), id).stream().findFirst();
    }

    private static String metadata(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " 不能为空或包含非法字符");
        }
        return value.trim();
    }

    private record StoredArtifact(String hash, byte[] bytes) { }
}
