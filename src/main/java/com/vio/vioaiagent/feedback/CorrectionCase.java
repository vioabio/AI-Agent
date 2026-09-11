package com.vio.vioaiagent.feedback;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户反馈及其纠错工单的不可变聚合。持久化层保存同一快照和迁移历史，
 * 服务层只能通过 {@link FeedbackStateMachine} 生成新快照。
 */
public record CorrectionCase(
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

    public CorrectionCase {
        require(feedbackId, "feedbackId");
        require(answerId, "answerId");
        require(traceId, "traceId");
        require(errorType, "errorType");
        require(userId, "userId");
        dataReleaseId = normalizeRelease(dataReleaseId);
        knowledgeReleaseId = normalizeRelease(knowledgeReleaseId);
        indexReleaseId = normalizeRelease(indexReleaseId);
        feedbackType = feedbackType == null ? FeedbackType.CORRECTION : feedbackType;
        priority = priority == null ? FeedbackPriority.P2 : priority;
        status = status == null ? FeedbackState.SUBMITTED : status;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
        if (version < 0) throw new IllegalArgumentException("反馈版本不能为负数");
        evidence = immutableMap(evidence);
        history = history == null ? List.of() : List.copyOf(history);
    }

    public static CorrectionCase submit(String feedbackId, String answerId, String traceId,
                                        String errorType, Map<String, Object> evidence, String userId,
                                        String dataReleaseId, String knowledgeReleaseId, String indexReleaseId,
                                        FeedbackType feedbackType, FeedbackPriority priority, Instant now) {
        Instant at = now == null ? Instant.now() : now;
        CorrectionCase value = new CorrectionCase(feedbackId, answerId, traceId, errorType, evidence, userId,
                dataReleaseId, knowledgeReleaseId, indexReleaseId, feedbackType, priority,
                FeedbackState.SUBMITTED, null, null, null, at, at, 0, List.of());
        return value.withHistory(FeedbackTransition.submitted(userId, traceId, at));
    }

    /** 兼容状态机和仓储重建快照；只追加一条已校验的迁移记录。 */
    public CorrectionCase transitioned(FeedbackState nextStatus, String nextFixReleaseId,
                                       String nextEvaluationResult, FeedbackTransition transition) {
        if (transition == null) throw new IllegalArgumentException("反馈迁移记录不能为空");
        List<FeedbackTransition> nextHistory = new ArrayList<>(history);
        nextHistory.add(transition);
        return new CorrectionCase(feedbackId, answerId, traceId, errorType, evidence, userId,
                dataReleaseId, knowledgeReleaseId, indexReleaseId, feedbackType, priority,
                nextStatus, assignedTo, nextFixReleaseId, nextEvaluationResult,
                createdAt, transition.changedAt(), version + 1, nextHistory);
    }

    public CorrectionCase withHistory(FeedbackTransition transition) {
        List<FeedbackTransition> nextHistory = new ArrayList<>(history);
        nextHistory.add(transition);
        return new CorrectionCase(feedbackId, answerId, traceId, errorType, evidence, userId,
                dataReleaseId, knowledgeReleaseId, indexReleaseId, feedbackType, priority,
                status, assignedTo, fixReleaseId, evaluationResult, createdAt, updatedAt,
                version, nextHistory);
    }

    /** API/代码中有时称状态为 state；保留语义别名而不改变 JSON 字段 status。 */
    public FeedbackState state() {
        return status;
    }

    private static String normalizeRelease(String value) {
        // 缺失血缘保持 null；unknown 不能伪装成一个可发布版本。
        if (value == null || value.isBlank() || "unknown".equalsIgnoreCase(value.trim())) return null;
        return value.trim();
    }

    private static Map<String, Object> immutableMap(Map<String, Object> value) {
        if (value == null || value.isEmpty()) return Map.of();
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
    }
}
