package com.vio.vioaiagent.rag.promotion;

import com.vio.vioaiagent.rag.domain.KnowledgeRelease;
import com.vio.vioaiagent.rag.domain.VectorIndexRelease;

import java.util.Objects;
import java.util.Optional;

/**
 * N1b 发布质量门禁。
 *
 * <p>默认没有可用的评测报告仓储，因此默认结果是拒绝（fail-closed）。门禁
 * 不接受 Controller 传入的几个布尔值作为证据，而是按 evaluationRunId 回读
 * 不可变报告并逐一比对 report hash、suite/model 以及 data/knowledge/index
 * 三段血缘。</p>
 */
public class ReleaseQualityGate {

    private volatile EvaluationReportRepository reports = EvaluationReportRepository.unavailable();

    /** Spring 默认构造器：没有报告仓储时明确 fail-closed。 */
    public ReleaseQualityGate() {
    }

    public ReleaseQualityGate(EvaluationReportRepository reports) {
        this.reports = reports == null ? EvaluationReportRepository.unavailable() : reports;
    }

    public ReleaseQualityGate(EvaluationReport report) {
        this(EvaluationReportRepository.fixed(report));
    }

    public GateResult evaluate(ReleasePromotionRequest request,
                               KnowledgeRelease release,
                               VectorIndexRelease index) {
        if (request == null) return reject("PROMOTION_REQUEST_MISSING", "promotion 请求不能为空", null);
        if (release == null) return reject("KNOWLEDGE_RELEASE_MISSING", "knowledge release 不存在", null);
        if (index == null) return reject("INDEX_RELEASE_MISSING", "index release 不存在", null);

        if (!request.knowledgeReleaseId().equals(release.id())
                || !request.indexReleaseId().equals(index.id())
                || !request.dataReleaseId().equals(release.dataReleaseId())
                || !index.knowledgeReleaseId().equals(release.id())) {
            return reject("RELEASE_LINEAGE_MISMATCH", "promotion 请求与 release/index 血缘不一致", null);
        }
        if (release.status() != KnowledgeRelease.ReleaseStatus.CANDIDATE
                && release.status() != KnowledgeRelease.ReleaseStatus.ACTIVE) {
            return reject("KNOWLEDGE_RELEASE_NOT_CANDIDATE", "knowledge release 不是可发布候选状态", null);
        }
        if (index.status() != VectorIndexRelease.IndexStatus.CANDIDATE
                && index.status() != VectorIndexRelease.IndexStatus.BUILDING
                && index.status() != VectorIndexRelease.IndexStatus.ACTIVE) {
            return reject("INDEX_RELEASE_NOT_CANDIDATE", "index release 不是可发布候选状态", null);
        }
        EvaluationReportRepository repository = reports;
        if (repository == null || !repository.available()) {
            return reject("EVALUATION_REPORT_UNAVAILABLE", "评测报告仓储不可用，拒绝发布", null);
        }
        final Optional<EvaluationReport> found;
        try {
            found = repository.findByEvaluationRunId(request.evaluationRunId());
        } catch (RuntimeException ex) {
            // 不把数据库/对象存储异常转成“评测通过”；只返回稳定错误码，避免
            // 将连接串、签名 URL 或驱动堆栈写入客户端响应。
            return reject("EVALUATION_REPORT_READ_FAILED", "评测报告读取失败，拒绝发布", null);
        }
        if (found == null || found.isEmpty()) {
            return reject("EVALUATION_REPORT_NOT_FOUND", "找不到指定 evaluationRun 的不可变报告", null);
        }
        EvaluationReport report = found.get();
        if (!request.evaluationRunId().equals(report.evaluationRunId())) {
            return reject("EVALUATION_RUN_MISMATCH", "evaluationRunId 与报告不一致", report);
        }
        if (!request.reportHash().equals(report.reportHash())) {
            return reject("EVALUATION_REPORT_HASH_MISMATCH", "评测报告 hash 不一致", report);
        }
        if (!request.dataReleaseId().equals(report.dataReleaseId())
                || !request.knowledgeReleaseId().equals(report.knowledgeReleaseId())
                || !request.indexReleaseId().equals(report.indexReleaseId())) {
            return reject("EVALUATION_REPORT_LINEAGE_MISMATCH", "评测报告未绑定当前三段 release", report);
        }
        if (!report.immutable()) {
            return reject("EVALUATION_REPORT_MUTABLE", "评测报告不是不可变快照", report);
        }
        if (!report.passed()) {
            return reject("EVALUATION_REPORT_FAILED", "评测报告未达到发布门槛", report);
        }
        if (report.sampleSize() <= 0 || !report.hasMetricEvidence() || !report.hasThresholdEvidence()) {
            return reject("EVALUATION_EVIDENCE_INCOMPLETE", "评测报告缺少样本、指标或阈值证据", report);
        }
        if (!report.meetsThresholds()) {
            return reject("EVALUATION_THRESHOLD_FAILED", "评测指标未达到报告声明的确定性阈值", report);
        }
        if (EvaluationReport.isPlaceholder(report.suiteVersion())
                || EvaluationReport.isPlaceholder(report.modelVersion())) {
            return reject("EVALUATION_VERSION_MISSING", "评测 suite/model 版本不完整", report);
        }
        return new GateResult(true, "QUALITY_GATE_PASSED", "质量门禁通过", report);
    }

    /** 兼容不同编排器命名；语义与 evaluate 完全一致。 */
    public GateResult check(ReleasePromotionRequest request,
                            KnowledgeRelease release,
                            VectorIndexRelease index) {
        return evaluate(request, release, index);
    }

    public GateResult validate(ReleasePromotionRequest request,
                               KnowledgeRelease release,
                               VectorIndexRelease index) {
        return evaluate(request, release, index);
    }

    public void assertPassed(ReleasePromotionRequest request,
                             KnowledgeRelease release,
                             VectorIndexRelease index) {
        GateResult result = evaluate(request, release, index);
        if (!result.allowed()) throw new ReleaseQualityGateException(result);
    }

    public void assertCanPromote(ReleasePromotionRequest request,
                                 KnowledgeRelease release,
                                 VectorIndexRelease index) {
        assertPassed(request, release, index);
    }

    public boolean allows(ReleasePromotionRequest request,
                          KnowledgeRelease release,
                          VectorIndexRelease index) {
        return evaluate(request, release, index).allowed();
    }

    private GateResult reject(String reasonCode, String message, EvaluationReport report) {
        return new GateResult(false, reasonCode, message, report);
    }

    public record GateResult(boolean allowed, String reasonCode, String message,
                             EvaluationReport report) {
        public GateResult {
            reasonCode = Objects.requireNonNullElse(reasonCode, "QUALITY_GATE_REJECTED");
            message = Objects.requireNonNullElse(message, "质量门禁拒绝发布");
        }
    }

    public static class ReleaseQualityGateException extends IllegalStateException {
        private final GateResult result;

        public ReleaseQualityGateException(GateResult result) {
            super(result == null ? "质量门禁拒绝发布" : result.reasonCode() + ": " + result.message());
            this.result = result;
        }

        public GateResult result() {
            return result;
        }
    }
}
