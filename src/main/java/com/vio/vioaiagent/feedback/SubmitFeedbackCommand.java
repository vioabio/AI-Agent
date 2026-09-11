package com.vio.vioaiagent.feedback;

import java.time.Instant;
import java.util.Map;

/** 提交反馈的内部命令；用户身份由认证上下文注入，不信任请求体中的 userId。 */
public record SubmitFeedbackCommand(
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
        String feedbackId,
        Instant createdAt) {

    public SubmitFeedbackCommand {
        answerId = require(answerId, "answerId");
        traceId = optional(traceId);
        errorType = fallback(errorType, "GENERAL");
        userId = require(userId, "userId");
        feedbackType = feedbackType == null ? FeedbackType.CORRECTION : feedbackType;
        priority = priority == null ? FeedbackPriority.P2 : priority;
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String fallback(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

}
