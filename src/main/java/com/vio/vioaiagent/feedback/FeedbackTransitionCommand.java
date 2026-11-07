package com.vio.vioaiagent.feedback;

import java.util.Set;

/** 运营状态迁移命令；角色集合由认证/权限适配层提供。 */
public record FeedbackTransitionCommand(
        String feedbackId,
        FeedbackState target,
        String actorId,
        Set<FeedbackRole> roles,
        String reason,
        String traceId,
        String fixReleaseId,
        String evaluationResult) {

    public FeedbackTransitionCommand {
        if (feedbackId == null || feedbackId.isBlank()) throw new IllegalArgumentException("feedbackId 不能为空");
        if (target == null) throw new IllegalArgumentException("target 不能为空");
        if (actorId == null || actorId.isBlank()) throw new IllegalArgumentException("actorId 不能为空");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason 不能为空");
        if (traceId == null || traceId.isBlank()) throw new IllegalArgumentException("traceId 不能为空");
    }
}
