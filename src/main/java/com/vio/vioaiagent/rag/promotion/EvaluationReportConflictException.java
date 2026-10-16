package com.vio.vioaiagent.rag.promotion;

/** The same evaluation ID cannot be reused for different producer bytes. */
public class EvaluationReportConflictException extends IllegalStateException {
    public EvaluationReportConflictException() {
        super("evaluationRunId 已存在且原始报告不可覆盖");
    }
}
