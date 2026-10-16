package com.vio.vioaiagent.rag.promotion;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 本地/测试用的不可变评测报告仓储；不会覆盖同一 evaluationRunId。 */
@Repository
@Primary
@Profile("!prod & !demo")
public class InMemoryEvaluationReportRepository implements EvaluationReportRepository {

    private final Map<String, EvaluationReport> reports = new ConcurrentHashMap<>();

    @Override
    public Optional<EvaluationReport> findByEvaluationRunId(String evaluationRunId) {
        if (evaluationRunId == null || evaluationRunId.isBlank()) return Optional.empty();
        return Optional.ofNullable(reports.get(evaluationRunId.trim()));
    }

    @Override
    public synchronized void save(EvaluationReport report) {
        if (report == null) throw new IllegalArgumentException("report 不能为空");
        EvaluationReport previous = reports.putIfAbsent(report.evaluationRunId(), report);
        if (previous != null && !previous.equals(report)) {
            throw new IllegalStateException("evaluationRunId 已存在且报告不可变: " + report.evaluationRunId());
        }
    }
}
