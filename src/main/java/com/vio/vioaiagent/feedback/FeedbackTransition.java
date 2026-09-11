package com.vio.vioaiagent.feedback;

import java.time.Instant;

/** 一次可审计的状态迁移；fromStatus 为空表示提交时的初始记录。 */
public record FeedbackTransition(
        FeedbackState fromStatus,
        FeedbackState toStatus,
        String actorId,
        FeedbackRole role,
        String reason,
        String traceId,
        Instant changedAt) {

    public FeedbackTransition {
        if (toStatus == null) throw new IllegalArgumentException("目标反馈状态不能为空");
        require(actorId, "actorId");
        if (role == null) throw new IllegalArgumentException("反馈操作角色不能为空");
        require(reason, "reason");
        require(traceId, "traceId");
        changedAt = changedAt == null ? Instant.now() : changedAt;
    }

    public static FeedbackTransition submitted(String userId, String traceId, Instant at) {
        return new FeedbackTransition(null, FeedbackState.SUBMITTED, userId,
                FeedbackRole.OPERATOR, "用户提交反馈", traceId, at);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
    }
}
