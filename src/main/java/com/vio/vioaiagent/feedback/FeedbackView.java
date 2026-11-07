package com.vio.vioaiagent.feedback;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 对外稳定反馈视图；不暴露任何原始凭据或内部数据库字段。 */
public record FeedbackView(
        String feedbackId,
        String answerId,
        String traceId,
        String errorType,
        Map<String, Object> evidence,
        String userId,
        String dataReleaseId,
        String knowledgeReleaseId,
        String indexReleaseId,
        FeedbackType feedbackType,
        FeedbackPriority priority,
        FeedbackState status,
        String assignedTo,
        String fixReleaseId,
        String evaluationResult,
        Instant createdAt,
        Instant updatedAt,
        long version,
        List<FeedbackTransition> history) {

    public static FeedbackView from(CorrectionCase value) {
        if (value == null) return null;
        return new FeedbackView(value.feedbackId(), value.answerId(), value.traceId(), value.errorType(),
                value.evidence(), value.userId(), value.dataReleaseId(), value.knowledgeReleaseId(),
                value.indexReleaseId(), value.feedbackType(), value.priority(), value.status(),
                value.assignedTo(), value.fixReleaseId(), value.evaluationResult(), value.createdAt(),
                value.updatedAt(), value.version(), value.history());
    }
}
