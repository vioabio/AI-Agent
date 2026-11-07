package com.vio.vioaiagent.rag.promotion;

import com.vio.vioaiagent.rag.catalog.KnowledgeCatalog;
import com.vio.vioaiagent.rag.domain.KnowledgeRelease;
import com.vio.vioaiagent.rag.domain.VectorIndexRelease;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * N1b promotion application service。
 *
 * <p>所有 active 指针切换都必须经由本服务：先回读候选 release 和不可变
 * evaluation report，写入独立 approval，再调用目录的事务性 activateIndex。
 * 旧的 {@code KnowledgeCatalog.activateIndex} 仍保留给底层迁移/回滚，但 HTTP
 * 发布入口在生产环境不会绕过本服务。</p>
 */
@Service
public class KnowledgePromotionService {

    private final KnowledgeCatalog catalog;
    private final ReleaseQualityGate qualityGate;
    private final KnowledgeReleaseApprovalRepository approvals;
    private final Clock clock;

    @Autowired
    public KnowledgePromotionService(KnowledgeCatalog catalog,
                                     ObjectProvider<ReleaseQualityGate> qualityGateProvider,
                                     ObjectProvider<KnowledgeReleaseApprovalRepository> approvalProvider) {
        this(catalog,
                qualityGateProvider == null ? null : qualityGateProvider.getIfAvailable(ReleaseQualityGate::new),
                approvalProvider == null ? null : approvalProvider.getIfAvailable(
                        KnowledgeReleaseApprovalRepository::unavailable), Clock.systemUTC());
    }

    public KnowledgePromotionService(KnowledgeCatalog catalog,
                                     ReleaseQualityGate qualityGate,
                                     KnowledgeReleaseApprovalRepository approvals) {
        this(catalog, qualityGate, approvals, Clock.systemUTC());
    }

    public KnowledgePromotionService(KnowledgeCatalog catalog,
                                     ReleaseQualityGate qualityGate,
                                     KnowledgeReleaseApprovalRepository approvals,
                                     Clock clock) {
        this.catalog = Objects.requireNonNull(catalog, "catalog 不能为空");
        this.qualityGate = qualityGate == null ? new ReleaseQualityGate() : qualityGate;
        this.approvals = approvals == null ? KnowledgeReleaseApprovalRepository.unavailable() : approvals;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** 显式 promotion；任何缺少报告/审批持久化的环境都会拒绝。 */
    @Transactional
    public PromotionResult promote(ReleasePromotionRequest request) {
        if (request == null) throw new PromotionRejectedException("PROMOTION_REQUEST_MISSING", "promotion 请求不能为空");
        VectorIndexRelease index = catalog.findIndex(request.indexReleaseId())
                .orElseThrow(() -> new PromotionRejectedException("INDEX_RELEASE_MISSING",
                        "找不到候选索引版本: " + request.indexReleaseId()));
        KnowledgeRelease release = catalog.findRelease(request.knowledgeReleaseId())
                .orElseThrow(() -> new PromotionRejectedException("KNOWLEDGE_RELEASE_MISSING",
                        "找不到候选知识版本: " + request.knowledgeReleaseId()));

        // 先做状态/血缘检查，避免把一个合法报告绑定到错误候选。
        if (!request.knowledgeReleaseId().equals(index.knowledgeReleaseId())
                || !request.knowledgeReleaseId().equals(release.id())
                || !request.dataReleaseId().equals(release.dataReleaseId())) {
            throw new PromotionRejectedException("RELEASE_LINEAGE_MISMATCH", "候选 release 血缘不一致");
        }
        if (!isCandidateOrActive(index.status())) {
            throw new PromotionRejectedException("INDEX_RELEASE_NOT_CANDIDATE", "索引不是 candidate/building 状态");
        }
        if (!isCandidateOrActive(release.status())) {
            throw new PromotionRejectedException("KNOWLEDGE_RELEASE_NOT_CANDIDATE", "知识 release 不是 candidate 状态");
        }

        ReleaseQualityGate.GateResult gate = qualityGate.evaluate(request, release, index);
        if (!gate.allowed()) {
            throw new PromotionRejectedException(gate.reasonCode(), gate.message());
        }
        if (approvals == null || !approvals.durable()) {
            throw new PromotionRejectedException("APPROVAL_STORE_UNAVAILABLE", "审批仓储不可用，拒绝切换 active");
        }

        String approvalId = deterministicApprovalId(request);
        KnowledgeReleaseApproval candidate = KnowledgeReleaseApproval.from(request, approvalId,
                Instant.now(clock));
        final KnowledgeReleaseApprovalRepository.ReservationResult persisted;
        try {
            persisted = approvals.createOrGet(candidate);
        } catch (RuntimeException ex) {
            throw new PromotionRejectedException("APPROVAL_WRITE_FAILED", "审批写入失败，拒绝切换 active", ex);
        }
        if (persisted == null || persisted.approval() == null) {
            throw new PromotionRejectedException("APPROVAL_READBACK_FAILED", "审批写入后无法回读，拒绝切换 active");
        }

        try {
            catalog.activateIndex(index.id());
        } catch (RuntimeException ex) {
            // approval 保留为不可变事实；候选仍由目录状态决定，后续可重试同一
            // request，不删除线上索引或审批历史。
            throw new PromotionRejectedException("INDEX_ACTIVATION_FAILED", "active 指针切换失败", ex);
        }
        boolean alreadyActive = index.status() == VectorIndexRelease.IndexStatus.ACTIVE
                && release.status() == KnowledgeRelease.ReleaseStatus.ACTIVE;
        return new PromotionResult(index.id(), release.id(), request.dataReleaseId(),
                request.evaluationRunId(), request.reportHash(), persisted.approval().approvalId(),
                alreadyActive ? Status.ALREADY_ACTIVE : Status.PUBLISHED, gate.reasonCode(),
                Instant.now(clock));
    }

    /** 兼容旧程序化调用；没有完整血缘/报告时刻意 fail-closed。 */
    public PromotionResult promote(String indexReleaseId, String knowledgeReleaseId,
                                   String dataReleaseId, String evaluationRunId,
                                   String reportHash, String reviewer) {
        return promote(new ReleasePromotionRequest(indexReleaseId, knowledgeReleaseId, dataReleaseId,
                evaluationRunId, reportHash, reviewer));
    }

    private boolean isCandidateOrActive(VectorIndexRelease.IndexStatus status) {
        return status == VectorIndexRelease.IndexStatus.CANDIDATE
                || status == VectorIndexRelease.IndexStatus.BUILDING
                || status == VectorIndexRelease.IndexStatus.ACTIVE;
    }

    private boolean isCandidateOrActive(KnowledgeRelease.ReleaseStatus status) {
        return status == KnowledgeRelease.ReleaseStatus.CANDIDATE
                || status == KnowledgeRelease.ReleaseStatus.ACTIVE;
    }

    private String deterministicApprovalId(ReleasePromotionRequest request) {
        String material = String.join("\u001f", request.knowledgeReleaseId(), request.indexReleaseId(),
                request.dataReleaseId(), request.evaluationRunId(), request.reportHash(), request.reviewer());
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
            return "approval-" + digest.substring(0, 48);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK 不支持 SHA-256", ex);
        }
    }

    public enum Status { PUBLISHED, ALREADY_ACTIVE }

    public record PromotionResult(String indexReleaseId, String knowledgeReleaseId,
                                  String dataReleaseId, String evaluationRunId,
                                  String reportHash, String approvalId, Status status,
                                  String gateReasonCode, Instant promotedAt) {
        public PromotionResult {
            status = status == null ? Status.PUBLISHED : status;
            promotedAt = promotedAt == null ? Instant.now() : promotedAt;
        }
    }

    public static class PromotionRejectedException extends IllegalStateException {
        private final String reasonCode;

        public PromotionRejectedException(String reasonCode, String message) {
            super(message);
            this.reasonCode = reasonCode == null ? "PROMOTION_REJECTED" : reasonCode;
        }

        public PromotionRejectedException(String reasonCode, String message, Throwable cause) {
            super(message, cause);
            this.reasonCode = reasonCode == null ? "PROMOTION_REJECTED" : reasonCode;
        }

        public String reasonCode() {
            return reasonCode;
        }
    }
}
