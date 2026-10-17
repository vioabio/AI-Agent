package com.vio.vioaiagent.rag.ingestion;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline guard for the durable ingestion schema.  PostgreSQL integration tests
 * remain the source of truth for SQL execution; this test catches accidental
 * removal of the tables and constraints during ordinary Maven test runs.
 */
class KnowledgeIngestionMigrationContractTest {

    @Test
    void durableIngestionMigrationContainsRequiredTablesAndGuards() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream("/db/migration/V8__durable_knowledge_ingestion_jobs.sql")) {
            assertTrue(stream != null, "V8 ingestion migration must be packaged");
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        for (String table : new String[]{
                "knowledge_ingestion_job",
                "knowledge_ingestion_attempt",
                "knowledge_ingestion_outbox",
                "knowledge_ingestion_dlq",
                "vector_build_job"}) {
            assertTrue(sql.contains("create table if not exists " + table),
                    () -> "missing durable ingestion table declaration: " + table);
        }
        assertTrue(sql.contains("unique (source_id, idempotency_key)"),
                "reservation idempotency must be database-enforced");
        assertTrue(sql.contains("retention_until"),
                "DLQ rows must carry an explicit retention boundary");
    }
}
