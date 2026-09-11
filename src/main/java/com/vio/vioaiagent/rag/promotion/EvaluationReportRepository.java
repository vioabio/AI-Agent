package com.vio.vioaiagent.rag.promotion;

import java.util.Optional;

/** N6a 评测报告的只读/写入边界；发布服务只按 evaluationRunId 回读。 */
public interface EvaluationReportRepository {

    Optional<EvaluationReport> findByEvaluationRunId(String evaluationRunId);

    /** 离线 producer 或测试适配器使用；线上实现可仅暴露读取。 */
    default void save(EvaluationReport report) {
        throw new UnsupportedOperationException("当前评测报告仓储只读");
    }

    default boolean available() {
        return true;
    }

    static EvaluationReportRepository unavailable() {
        return new EvaluationReportRepository() {
            @Override
            public Optional<EvaluationReport> findByEvaluationRunId(String evaluationRunId) {
                return Optional.empty();
            }

            @Override
            public boolean available() {
                return false;
            }
        };
    }

    static EvaluationReportRepository fixed(EvaluationReport report) {
        if (report == null) return unavailable();
        return new EvaluationReportRepository() {
            @Override
            public Optional<EvaluationReport> findByEvaluationRunId(String evaluationRunId) {
                return report.evaluationRunId().equals(evaluationRunId == null ? null : evaluationRunId.trim())
                        ? Optional.of(report) : Optional.empty();
            }
        };
    }
}
