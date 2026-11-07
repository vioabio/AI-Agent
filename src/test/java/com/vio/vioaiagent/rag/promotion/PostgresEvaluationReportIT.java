package com.vio.vioaiagent.rag.promotion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real migration, restart readback, append-only and concurrent conflict evidence. */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class PostgresEvaluationReportIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("vio_report_it").withUsername("vio_it").withPassword("vio_it_password");
    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
    }

    @Test
    void roundTripAcrossRepositoryInstancesAndReplayPreservesFirstImporter() throws Exception {
        byte[] bytes = EvaluationReportArtifactDecoderTest.readyArtifact("pg-report-replay", null);
        EvaluationReport first = importBytes(bytes);
        assertEquals(first, repository().findByEvaluationRunId(first.evaluationRunId()).orElseThrow());
        repository().importArtifact(bytes, first.reportHash(), "retry-source", "retry-actor");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM evaluation_report WHERE evaluation_run_id=?",
                Integer.class, first.evaluationRunId()));
        assertEquals("quality-it", jdbc.queryForObject("SELECT imported_by FROM evaluation_report WHERE evaluation_run_id=?",
                String.class, first.evaluationRunId()));
    }

    @Test
    void conflictingOriginalBytesCannotOverwriteTheSameRun() throws Exception {
        byte[] bytes = EvaluationReportArtifactDecoderTest.readyArtifact("pg-report-conflict", null);
        EvaluationReport first = importBytes(bytes);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode changed = (ObjectNode) mapper.readTree(bytes);
        changed.put("generatedAt", "2026-10-10T01:00:00Z");
        byte[] mutation = mapper.writeValueAsBytes(changed);
        assertThrows(EvaluationReportConflictException.class, () -> importBytes(mutation));
        assertEquals(first, repository().findByEvaluationRunId(first.evaluationRunId()).orElseThrow());
    }

    @Test
    void databaseRejectsUpdateAndDeleteEvenOutsideTheRepository() throws Exception {
        byte[] bytes = EvaluationReportArtifactDecoderTest.readyArtifact("pg-report-append-only", null);
        EvaluationReport report = importBytes(bytes);
        assertThrows(DataAccessException.class,
                () -> jdbc.update("UPDATE evaluation_report SET source='changed' WHERE evaluation_run_id=?", report.evaluationRunId()));
        assertThrows(DataAccessException.class,
                () -> jdbc.update("DELETE FROM evaluation_report WHERE evaluation_run_id=?", report.evaluationRunId()));
        assertEquals(report, repository().findByEvaluationRunId(report.evaluationRunId()).orElseThrow());
    }

    @Test
    void concurrentIdenticalImportsProduceOneImmutableRow() throws Exception {
        byte[] bytes = EvaluationReportArtifactDecoderTest.readyArtifact("pg-report-concurrent", null);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> importBytes(bytes));
            var second = executor.submit(() -> importBytes(bytes));
            assertEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM evaluation_report WHERE evaluation_run_id=?",
                Integer.class, "pg-report-concurrent"));
    }

    @Test
    void persistedBaselineAllowsACompleteCandidateButInitialBaselineDoesNotPass() throws Exception {
        EvaluationReport baseline = importBytes(EvaluationReportArtifactDecoderTest.readyArtifact("pg-budget-baseline", null));
        assertFalse(baseline.passed());
        EvaluationReport candidate = importBytes(EvaluationReportArtifactDecoderTest.readyArtifact("pg-budget-candidate", baseline));
        assertTrue(candidate.hasUsableEvidence());
        assertEquals(candidate, repository().findByEvaluationRunId(candidate.evaluationRunId()).orElseThrow());
    }

    @Test
    void directSqlCorruptionIsDetectedBeforeReleaseEvidenceIsReturned() throws Exception {
        byte[] bytes = EvaluationReportArtifactDecoderTest.readyArtifact("pg-corrupt-report", null);
        jdbc.update("""
                INSERT INTO evaluation_report (evaluation_run_id,report_hash,artifact_bytes,source,imported_by)
                VALUES (?,?,?,?,?)
                """, "pg-corrupt-report", "sha256:" + "0".repeat(64), bytes, "synthetic-corruption", "it");
        assertThrows(IllegalArgumentException.class,
                () -> repository().findByEvaluationRunId("pg-corrupt-report"));
    }

    static JdbcEvaluationReportRepository repository() { return new JdbcEvaluationReportRepository(jdbc, new ObjectMapper()); }
    static EvaluationReport importBytes(byte[] bytes) {
        return repository().importArtifact(bytes, EvaluationReportArtifactDecoder.hash(bytes), "ci/test/metrics.json", "quality-it");
    }
}
