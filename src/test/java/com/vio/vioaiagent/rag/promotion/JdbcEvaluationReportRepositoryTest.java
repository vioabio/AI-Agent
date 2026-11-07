package com.vio.vioaiagent.rag.promotion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Store boundary regressions; actual PostgreSQL concurrency/triggers are covered in the IT. */
class JdbcEvaluationReportRepositoryTest {
    @Test
    void replayIsImmutableAndASeparateRepositoryCanReadIt() throws Exception {
        MemoryJdbc jdbc = new MemoryJdbc();
        JdbcEvaluationReportRepository writer = repository(jdbc);
        byte[] baselineBytes = EvaluationReportArtifactDecoderTest.readyArtifact("stored-baseline", null);
        EvaluationReport baseline = writer.importArtifact(baselineBytes,
                EvaluationReportArtifactDecoder.hash(baselineBytes), "ci/run-1/metrics.json", "quality-user");
        assertFalse(baseline.passed(), "The initial budget baseline is archival evidence, not a release pass");
        byte[] bytes = EvaluationReportArtifactDecoderTest.readyArtifact("stored-candidate", baseline);
        EvaluationReport candidate = writer.importArtifact(bytes, EvaluationReportArtifactDecoder.hash(bytes),
                "ci/run-2/metrics.json", "quality-user");
        assertTrue(candidate.hasUsableEvidence());
        assertEquals(candidate, repository(jdbc).findByEvaluationRunId(" stored-candidate ").orElseThrow());
        assertEquals(candidate, writer.importArtifact(bytes, candidate.reportHash(), "retry", "other-user"));
        assertEquals(2, jdbc.rows.size());
        ObjectNode changed = (ObjectNode) new ObjectMapper().readTree(bytes);
        changed.put("generatedAt", java.time.Instant.parse(changed.path("generatedAt").asText()).plusSeconds(1).toString());
        byte[] mutation = new ObjectMapper().writeValueAsBytes(changed);
        assertThrows(EvaluationReportConflictException.class, () -> writer.importArtifact(mutation,
                EvaluationReportArtifactDecoder.hash(mutation), "changed", "quality-user"));
        assertEquals(candidate, writer.findByEvaluationRunId(candidate.evaluationRunId()).orElseThrow());
    }

    @Test
    void corruptOriginalBytesAreNeverReturnedAsUsableEvidence() throws Exception {
        MemoryJdbc jdbc = new MemoryJdbc();
        byte[] bytes = EvaluationReportArtifactDecoderTest.readyArtifact("corrupt-report", null);
        jdbc.rows.put("corrupt-report", new Row("sha256:" + "0".repeat(64), bytes));
        assertThrows(IllegalArgumentException.class,
                () -> repository(jdbc).findByEvaluationRunId("corrupt-report"));
    }

    @Test
    void rejectsMissingOrCyclicBaselinesAndArbitraryProjectionWrites() throws Exception {
        MemoryJdbc jdbc = new MemoryJdbc();
        JdbcEvaluationReportRepository repository = repository(jdbc);
        byte[] missing = EvaluationReportArtifactDecoderTest.readyArtifact("missing-baseline-report",
                EvaluationReportArtifactDecoderTest.baselineReport());
        assertThrows(IllegalArgumentException.class, () -> repository.importArtifact(missing,
                EvaluationReportArtifactDecoder.hash(missing), "ci/run", "quality"));
        assertThrows(UnsupportedOperationException.class,
                () -> repository.save(EvaluationReportArtifactDecoderTest.baselineReport()));
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode a = (ObjectNode) mapper.readTree(missing);
        a.put("evaluationRunId", "cycle-a").put("baselineEvaluationRunId", "cycle-b");
        ObjectNode b = a.deepCopy().put("evaluationRunId", "cycle-b").put("baselineEvaluationRunId", "cycle-a");
        byte[] aBytes = mapper.writeValueAsBytes(a);
        byte[] bBytes = mapper.writeValueAsBytes(b);
        jdbc.rows.put("cycle-a", new Row(EvaluationReportArtifactDecoder.hash(aBytes), aBytes));
        jdbc.rows.put("cycle-b", new Row(EvaluationReportArtifactDecoder.hash(bBytes), bBytes));
        assertThrows(IllegalStateException.class, () -> repository.findByEvaluationRunId("cycle-a"));
    }

    @Test
    void storageKeyCannotSubstituteAnotherEvaluationAndMetadataIsRequired() throws Exception {
        MemoryJdbc jdbc = new MemoryJdbc();
        byte[] bytes = EvaluationReportArtifactDecoderTest.readyArtifact("real-run", null);
        jdbc.rows.put("different-run", new Row(EvaluationReportArtifactDecoder.hash(bytes), bytes));
        JdbcEvaluationReportRepository repository = repository(jdbc);
        assertThrows(IllegalStateException.class, () -> repository.findByEvaluationRunId("different-run"));
        assertThrows(IllegalArgumentException.class, () -> repository.importArtifact(bytes,
                EvaluationReportArtifactDecoder.hash(bytes), "", "quality"));
        assertThrows(IllegalArgumentException.class, () -> repository.importArtifact(bytes,
                EvaluationReportArtifactDecoder.hash(bytes), "ci\nrun", "quality"));
        assertTrue(repository.findByEvaluationRunId(null).isEmpty());
    }

    @Test
    void productionWiresJdbcInsteadOfAnUnavailableOrInMemoryStore() {
        new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=prod")
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withUserConfiguration(JdbcEvaluationReportRepository.class,
                        InMemoryEvaluationReportRepository.class, KnowledgePromotionConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(EvaluationReportRepository.class);
                    assertThat(context.getBean(EvaluationReportRepository.class))
                            .isInstanceOf(JdbcEvaluationReportRepository.class);
                    assertThat(context).hasSingleBean(ReleaseQualityGate.class);
                });
    }

    private static JdbcEvaluationReportRepository repository(JdbcTemplate jdbc) {
        return new JdbcEvaluationReportRepository(jdbc, new ObjectMapper());
    }

    private record Row(String hash, byte[] bytes) { }

    private static class MemoryJdbc extends JdbcTemplate {
        final Map<String, Row> rows = new ConcurrentHashMap<>();
        @Override
        public int update(String sql, Object... args) {
            return rows.putIfAbsent((String) args[0], new Row((String) args[1], ((byte[]) args[2]).clone())) == null ? 1 : 0;
        }
        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            Row row = rows.get((String) args[0]);
            if (row == null) return List.of();
            try {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getString("report_hash")).thenReturn(row.hash());
                when(rs.getBytes("artifact_bytes")).thenReturn(row.bytes().clone());
                return List.of(mapper.mapRow(rs, 0));
            } catch (java.sql.SQLException ex) { throw new AssertionError(ex); }
        }
    }
}
