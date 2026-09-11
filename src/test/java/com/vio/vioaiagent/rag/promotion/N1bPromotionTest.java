package com.vio.vioaiagent.rag.promotion;

import com.vio.vioaiagent.rag.catalog.InMemoryKnowledgeCatalog;
import com.vio.vioaiagent.rag.domain.DocumentChunk;
import com.vio.vioaiagent.rag.domain.DocumentVersion;
import com.vio.vioaiagent.rag.domain.KnowledgeDocument;
import com.vio.vioaiagent.rag.domain.KnowledgeRelease;
import com.vio.vioaiagent.rag.domain.KnowledgeSource;
import com.vio.vioaiagent.rag.domain.VectorIndexRelease;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** N1b：没有不可变评测证据时不能切 active，成功 promotion 可重放。 */
class N1bPromotionTest {

    @Test
    void defaultGateFailsClosedWhenReportStoreIsUnavailable() {
        InMemoryKnowledgeCatalog catalog = preparedCatalog();
        KnowledgePromotionService service = new KnowledgePromotionService(catalog,
                new ReleaseQualityGate(), new InMemoryKnowledgeReleaseApprovalRepository());
        assertThrows(KnowledgePromotionService.PromotionRejectedException.class,
                () -> service.promote(request()));
        assertTrue(catalog.activeIndex().isEmpty());
    }

    @Test
    void promotionRequiresExactLineageAndCreatesImmutableApproval() {
        InMemoryKnowledgeCatalog catalog = preparedCatalog();
        InMemoryEvaluationReportRepository reports = new InMemoryEvaluationReportRepository();
        reports.save(report());
        InMemoryKnowledgeReleaseApprovalRepository approvals = new InMemoryKnowledgeReleaseApprovalRepository();
        KnowledgePromotionService service = new KnowledgePromotionService(catalog,
                new ReleaseQualityGate(reports), approvals);

        KnowledgePromotionService.PromotionResult first = service.promote(request());
        assertEquals(KnowledgePromotionService.Status.PUBLISHED, first.status());
        assertEquals("index-n1b", catalog.activeIndex().orElseThrow().id());
        assertTrue(approvals.findByApprovalId(first.approvalId()).isPresent());

        // 同一 evaluation/report/release 请求只复用同一 approval，不生成第二条事实。
        KnowledgePromotionService.PromotionResult retry = service.promote(request());
        assertEquals(KnowledgePromotionService.Status.ALREADY_ACTIVE, retry.status());
        assertEquals(first.approvalId(), retry.approvalId());
    }

    @Test
    void reportLineageMismatchLeavesCandidateUntouched() {
        InMemoryKnowledgeCatalog catalog = preparedCatalog();
        InMemoryEvaluationReportRepository reports = new InMemoryEvaluationReportRepository();
        reports.save(new EvaluationReport("eval-n1b", "sha256:wrong", "golden-v1", "model-v1",
                "data-n1b", "knowledge-n1b", "index-other", 10, true, true, Instant.now(),
                Map.of("recallAt5", .99), Map.of("recallAt5", ">=0.95")));
        KnowledgePromotionService service = new KnowledgePromotionService(catalog,
                new ReleaseQualityGate(reports), new InMemoryKnowledgeReleaseApprovalRepository());

        KnowledgePromotionService.PromotionRejectedException failure = assertThrows(
                KnowledgePromotionService.PromotionRejectedException.class,
                () -> service.promote(request()));
        assertEquals("EVALUATION_REPORT_HASH_MISMATCH", failure.reasonCode());
        assertFalse(catalog.activeIndex().isPresent());
        assertEquals(VectorIndexRelease.IndexStatus.CANDIDATE,
                catalog.findIndex("index-n1b").orElseThrow().status());
    }

    private ReleasePromotionRequest request() {
        return new ReleasePromotionRequest("index-n1b", "knowledge-n1b", "data-n1b",
                "eval-n1b", "sha256:report-n1b", "reviewer-n1b", "golden set passed", Instant.now());
    }

    private EvaluationReport report() {
        return new EvaluationReport("eval-n1b", "sha256:report-n1b", "golden-v1", "model-v1",
                "data-n1b", "knowledge-n1b", "index-n1b", 10, true, true, Instant.now(),
                Map.of("recallAt5", .99, "citationAccuracy", .98),
                Map.of("recallAt5", ">=0.95", "citationAccuracy", ">=0.95"));
    }

    private InMemoryKnowledgeCatalog preparedCatalog() {
        InMemoryKnowledgeCatalog catalog = new InMemoryKnowledgeCatalog();
        Instant now = Instant.now();
        catalog.saveSource(new KnowledgeSource("source-n1b", "https://example.invalid/n1b", "官方",
                "CC-BY-4.0", KnowledgeSource.TrustLevel.OFFICIAL, "operator", now, now));
        catalog.saveDocument(new KnowledgeDocument("doc-n1b", "source-n1b", "guide/n1b", "zh-CN",
                "sv-1", "singles", "rules-1", null, null, now));
        DocumentVersion version = new DocumentVersion("version-n1b", "doc-n1b", "v1", "checksum-n1b",
                "parser-1", "schema-1", "content-1", DocumentVersion.ReviewStatus.APPROVED,
                now, null, 10, Map.of());
        catalog.saveVersion(version);
        DocumentChunk chunk = new DocumentChunk("chunk-n1b", version.id(), 0, "宝可梦攻略证据",
                "hash-n1b", Map.of(), "embedding-v1", 3, DocumentChunk.EmbeddingStatus.READY);
        catalog.saveChunks(List.of(chunk));
        KnowledgeRelease release = new KnowledgeRelease("knowledge-n1b", "2026.09.n1b",
                KnowledgeRelease.ReleaseStatus.CANDIDATE, "parser-1", "embedding-v1", "data-n1b",
                10, .99, .98, .99, now, null);
        VectorIndexRelease index = new VectorIndexRelease("index-n1b", release.id(), "pgvector",
                "embedding-v1", 3, VectorIndexRelease.IndexStatus.CANDIDATE, "checksum-index-n1b", now, null);
        catalog.saveRelease(release);
        catalog.saveIndex(index);
        catalog.bindDocumentVersionToKnowledgeRelease(release.id(), version.id());
        catalog.bindChunksToIndexRelease(index.id(), List.of(chunk.id()));
        return catalog;
    }
}
