package com.vio.vioaiagent.rag.promotion;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline guard for the append-only evidence and approval tables used by N1b. */
class KnowledgePromotionMigrationContractTest {

    @Test
    void approvalAndReportMigrationsRemainAppendOnlyAndUniquelyBound() throws IOException {
        String approval = read("/db/migration/V23__knowledge_release_approval.sql");
        String report = read("/db/migration/V26__immutable_evaluation_reports.sql");

        assertTrue(approval.contains("create table if not exists knowledge_release_approval"));
        assertTrue(approval.contains("trg_knowledge_release_approval_append_only"));
        assertTrue(approval.contains("revoke update, delete on knowledge_release_approval"));
        assertTrue(approval.contains("unique (knowledge_release_id, index_release_id, evaluation_run_id, report_hash)"));

        assertTrue(report.contains("create table evaluation_report"));
        assertTrue(report.contains("trg_evaluation_report_append_only"));
        assertTrue(report.contains("revoke update, delete on evaluation_report"));
        assertTrue(report.contains("evaluation_run_id varchar(128) primary key"));
    }

    private static String read(String resource) throws IOException {
        try (var stream = KnowledgePromotionMigrationContractTest.class.getResourceAsStream(resource)) {
            assertTrue(stream != null, () -> "migration must be packaged: " + resource);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
