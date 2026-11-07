package com.vio.vioaiagent.rag.ingestion;

import com.vio.vioaiagent.rag.catalog.KnowledgeCatalog;
import com.vio.vioaiagent.rag.domain.DocumentVersion;
import com.vio.vioaiagent.rag.domain.KnowledgeRelease;
import com.vio.vioaiagent.rag.domain.VectorIndexRelease;
import com.vio.vioaiagent.rag.promotion.KnowledgePromotionService;
import com.vio.vioaiagent.rag.promotion.ReleasePromotionRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import com.vio.vioaiagent.rag.domain.IngestionBatch;

/**
 * M2 生命周期门面：把摄取、审核、候选 release、候选 index 和 active 指针切换
 * 收敛成可被 HTTP/作业编排复用的显式服务契约。该类不在 Spring 启动期间执行
 * 任何摄取或向量写入，调用方必须显式提交任务。
 */
@Service
public class KnowledgeLifecycleService {

    private final KnowledgeCatalog catalog;
    private final KnowledgeIngestionService ingestionService;
    private final KnowledgeIndexingService indexingService;
    /** N1b promotion gate；通过 setter 注入以保留旧的离线构造器。 */
    private volatile KnowledgePromotionService promotionService;

    public KnowledgeLifecycleService(KnowledgeCatalog catalog,
                                     KnowledgeIngestionService ingestionService,
                                     KnowledgeIndexingService indexingService) {
        this.catalog = catalog;
        this.ingestionService = ingestionService;
        this.indexingService = indexingService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setPromotionService(KnowledgePromotionService promotionService) {
        this.promotionService = promotionService;
    }

    public KnowledgeIngestionResult ingest(KnowledgeIngestionRequest request) {
        return ingestionService.ingest(request);
    }

    public Optional<IngestionBatch> findBatch(String batchId) {
        return catalog.findBatch(batchId);
    }

    /**
     * 审核文档版本。只有 PENDING/QUARANTINED 可进入 APPROVED/REJECTED；终态
     * 重复提交按幂等处理，试图把终态改成另一状态则拒绝，避免已发布版本被覆盖。
     */
    public DocumentVersion review(String versionId, DocumentVersion.ReviewStatus target,
                                  String reviewer, String reason) {
        if (target != DocumentVersion.ReviewStatus.APPROVED
                && target != DocumentVersion.ReviewStatus.REJECTED) {
            throw new IllegalArgumentException("审核结果只能是 APPROVED 或 REJECTED");
        }
        DocumentVersion current = catalog.findVersion(versionId)
                .orElseThrow(() -> new IllegalArgumentException("找不到文档版本: " + versionId));
        if (current.reviewStatus() == target) {
            return current;
        }
        if (current.reviewStatus() != DocumentVersion.ReviewStatus.PENDING
                && current.reviewStatus() != DocumentVersion.ReviewStatus.QUARANTINED) {
            throw new IllegalStateException("文档版本已审核完成，不能覆盖: " + current.reviewStatus());
        }
        Map<String, Object> metadata = new LinkedHashMap<>(current.metadata());
        if (reviewer != null && !reviewer.isBlank()) metadata.put("reviewer", reviewer.trim());
        if (reason != null && !reason.isBlank()) metadata.put("reviewReason", reason.trim());
        metadata.put("reviewedAt", Instant.now().toString());
        // 审核通过不等于发布：publishedAt 只能由 knowledge/index release
        // 的发布事务写入。否则一个尚未进入 active release 的版本会被检索侧
        // 误认为已发布，破坏“审核 → 候选索引 → 评测 → 发布”的生命周期边界。
        DocumentVersion reviewed = new DocumentVersion(current.id(), current.documentId(), current.versionNo(),
                current.checksum(), current.parserVersion(), current.schemaVersion(), current.contentVersion(),
                target, current.retrievedAt(), current.publishedAt(), current.sizeBytes(), metadata);
        catalog.saveVersion(reviewed);
        return reviewed;
    }

    /** 创建候选知识发布版本；CANDIDATE 是唯一允许的初始状态。 */
    public KnowledgeRelease createCandidateRelease(String id, String releaseNo, String parserVersion,
                                                    String embeddingModel, String dataReleaseId) {
        String safeId = id == null ? null : id.trim();
        if (catalog.findRelease(safeId).isPresent()) {
            // saveRelease 的生产 JDBC 实现支持幂等 upsert，但候选创建接口
            // 不能借此覆盖已 ACTIVE 的 release；版本身份冲突必须显式失败。
            throw new IllegalStateException("知识发布版本 ID 已存在，拒绝覆盖: " + safeId);
        }
        KnowledgeRelease release = new KnowledgeRelease(safeId, releaseNo, KnowledgeRelease.ReleaseStatus.CANDIDATE,
                parserVersion, embeddingModel, dataReleaseId, 0, null, null, null, Instant.now(), null);
        catalog.saveRelease(release);
        return release;
    }

    /** 创建候选向量索引；实际向量写入由 KnowledgeIndexingService.index 显式触发。 */
    public VectorIndexRelease createCandidateIndex(String id, String knowledgeReleaseId,
                                                   String vectorStore, String embeddingModel, int dimension) {
        String safeKnowledgeReleaseId = knowledgeReleaseId == null ? null : knowledgeReleaseId.trim();
        KnowledgeRelease release = catalog.findRelease(safeKnowledgeReleaseId)
                .orElseThrow(() -> new IllegalArgumentException("找不到知识发布版本: " + safeKnowledgeReleaseId));
        if (release.status() != KnowledgeRelease.ReleaseStatus.CANDIDATE) {
            throw new IllegalStateException("只能为 CANDIDATE 知识发布创建索引: " + release.status());
        }
        String safeId = id == null ? null : id.trim();
        if (catalog.findIndex(safeId).isPresent()) {
            // 同理，不能用相同 ID 改写 ACTIVE/ROLLED_BACK 索引的血缘。
            throw new IllegalStateException("向量索引版本 ID 已存在，拒绝覆盖: " + safeId);
        }
        VectorIndexRelease index = new VectorIndexRelease(safeId, safeKnowledgeReleaseId, vectorStore,
                embeddingModel, dimension, VectorIndexRelease.IndexStatus.CANDIDATE, null, Instant.now(), null);
        catalog.saveIndex(index);
        return index;
    }

    /** 评测通过后切换 active 指针；目录实现负责事务和旧版本回滚标记。 */
    public void publishIndex(String indexReleaseId) {
        if (promotionService != null) {
            // 生产 Spring 链路必须携带完整 N1b 证据；保留该方法仅为旧的
            // 程序化/回滚测试构造器兼容，不能在已装配 gate 时绕过它。
            throw new IllegalStateException("N1b 发布需要 ReleasePromotionRequest（evaluationRunId/reportHash/三段 release）");
        }
        catalog.activateIndex(indexReleaseId);
    }

    /** 通过 N1b 质量门禁、独立 approval 后原子切换 active。 */
    public KnowledgePromotionService.PromotionResult publishIndex(ReleasePromotionRequest request) {
        if (promotionService == null) {
            throw new IllegalStateException("N1b promotion 服务未装配，拒绝切换 active");
        }
        return promotionService.promote(request);
    }

    /** 对外暴露索引任务契约，强制调用方传入摄取结果和 release 血缘。 */
    public KnowledgeIndexingResult index(KnowledgeIngestionResult ingestion,
                                         String knowledgeReleaseId, String vectorIndexReleaseId) {
        return indexingService.index(ingestion, knowledgeReleaseId, vectorIndexReleaseId);
    }
}
