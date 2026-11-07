package com.vio.vioaiagent.rag.ingestion;

import com.vio.vioaiagent.rag.catalog.InMemoryKnowledgeCatalog;
import com.vio.vioaiagent.rag.domain.DocumentVersion;
import com.vio.vioaiagent.rag.domain.KnowledgeDocument;
import com.vio.vioaiagent.rag.domain.KnowledgeSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KnowledgeLifecycleServiceTest {

    @TempDir
    Path staging;

    @Test
    void idempotencyKeyReusesExistingBatch() throws Exception {
        InMemoryKnowledgeCatalog catalog = new InMemoryKnowledgeCatalog();
        KnowledgeIngestionService ingestion = new KnowledgeIngestionService(catalog);
        KnowledgeLifecycleService lifecycle = new KnowledgeLifecycleService(catalog, ingestion,
                new KnowledgeIndexingService(catalog, null, "test-embedding", 3));
        Path file = Files.writeString(staging.resolve("guide.md"), "sv singles guide");
        KnowledgeSource source = source();
        KnowledgeDocument document = document(source.id());
        KnowledgeIngestionRequest request = new KnowledgeIngestionRequest(file, source, document, "v1",
                "parser-1", "1", null, "request-1", 1200, 0);

        KnowledgeIngestionResult first = lifecycle.ingest(request);
        KnowledgeIngestionResult retry = lifecycle.ingest(request);

        assertEquals(first.batch().id(), retry.batch().id());
        assertEquals(1, first.chunks().size());
        assertEquals(0, retry.chunks().size());
    }

    @Test
    void reviewAllowsPendingToApprovedButRejectsOverwrite() throws Exception {
        InMemoryKnowledgeCatalog catalog = new InMemoryKnowledgeCatalog();
        KnowledgeIngestionService ingestion = new KnowledgeIngestionService(catalog);
        KnowledgeLifecycleService lifecycle = new KnowledgeLifecycleService(catalog, ingestion,
                new KnowledgeIndexingService(catalog, null, "test-embedding", 3));
        Path file = Files.writeString(staging.resolve("guide.md"), "sv singles guide");
        KnowledgeSource source = source();
        KnowledgeIngestionResult result = lifecycle.ingest(new KnowledgeIngestionRequest(file, source,
                document(source.id()), "v1", "parser-1", "1", null, "review-1", 1200, 0));

        DocumentVersion reviewed = lifecycle.review(result.documentVersion().id(),
                DocumentVersion.ReviewStatus.APPROVED, "reviewer-1", "来源可核验");
        assertEquals(DocumentVersion.ReviewStatus.APPROVED, reviewed.reviewStatus());
        // 审核本身不应伪造发布时间；发布时间由 release/index 发布事务决定。
        assertEquals(null, reviewed.publishedAt());
        assertThrows(IllegalStateException.class, () -> lifecycle.review(reviewed.id(),
                DocumentVersion.ReviewStatus.REJECTED, "reviewer-1", "overwrite"));
    }

    @Test
    void candidateIdentifiersCannotOverwriteExistingVersions() {
        InMemoryKnowledgeCatalog catalog = new InMemoryKnowledgeCatalog();
        KnowledgeLifecycleService lifecycle = new KnowledgeLifecycleService(catalog,
                new KnowledgeIngestionService(catalog),
                new KnowledgeIndexingService(catalog, null, "test-embedding", 3));

        lifecycle.createCandidateRelease("release-1", "2026.09.1", "parser-1",
                "test-embedding", null);
        assertThrows(IllegalStateException.class,
                () -> lifecycle.createCandidateRelease("release-1", "2026.09.2", "parser-2",
                        "other-embedding", null));

        lifecycle.createCandidateIndex("index-1", "release-1", "pgvector", "test-embedding", 3);
        assertThrows(IllegalStateException.class,
                () -> lifecycle.createCandidateIndex("index-1", "release-1", "pgvector", "other-embedding", 1536));
    }

    private KnowledgeSource source() {
        return new KnowledgeSource("source-lifecycle", "https://example.invalid/guide", "官方攻略",
                "CC-BY-4.0", KnowledgeSource.TrustLevel.OFFICIAL, "operator-1",
                Instant.now(), Instant.now());
    }

    private KnowledgeDocument document(String sourceId) {
        return new KnowledgeDocument("doc-lifecycle", sourceId, "guide/sv", "zh-CN", "sv-1",
                "singles", "rules-1", null, null, Instant.now());
    }
}
