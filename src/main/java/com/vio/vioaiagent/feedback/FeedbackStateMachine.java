package com.vio.vioaiagent.feedback;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 反馈纠错状态机的唯一决策点。迁移表与评测契约一一对应，未知边界默认拒绝。
 */
public final class FeedbackStateMachine {

    private static final Map<FeedbackState, Map<FeedbackState, Set<FeedbackRole>>> TRANSITIONS = transitions();

    private FeedbackStateMachine() {
    }

    public static CorrectionCase transition(CorrectionCase current, FeedbackState target,
                                             String actorId, Set<FeedbackRole> roles,
                                             String reason, String traceId) {
        return transition(current, target, actorId, roles, reason, traceId,
                current == null ? null : current.fixReleaseId(),
                current == null ? null : current.evaluationResult());
    }

    /**
     * 推进工单，并在发布边界强制要求修复版本和定向评测结果。
     */
    public static CorrectionCase transition(CorrectionCase current, FeedbackState target,
                                             String actorId, Set<FeedbackRole> roles,
                                             String reason, String traceId,
                                             String fixReleaseId, String evaluationResult) {
        if (current == null) throw new IllegalArgumentException("当前反馈工单不能为空");
        if (target == null) throw new IllegalArgumentException("目标反馈状态不能为空");
        if (current.status().isTerminal()) {
            throw new FeedbackTransitionException("CLOSED 工单不允许再次迁移", 409);
        }
        requireText(actorId, "actorId");
        requireText(reason, "reason");
        requireText(traceId, "traceId");
        Set<FeedbackRole> safeRoles = roles == null || roles.isEmpty() ? Set.of() : EnumSet.copyOf(roles);
        Map<FeedbackState, Set<FeedbackRole>> outgoing = TRANSITIONS.getOrDefault(current.status(), Map.of());
        Set<FeedbackRole> allowed = outgoing.get(target);
        if (allowed == null) {
            throw new FeedbackTransitionException(
                    "不允许的反馈状态迁移: " + current.status() + " -> " + target, 409);
        }
        FeedbackRole role = firstAllowed(allowed, safeRoles);
        if (role == null) {
            throw new FeedbackTransitionException("当前角色无权执行该反馈状态迁移", 403);
        }
        // 后续 VERIFIED/CLOSED 迁移不应擦掉发布时的修复版本和评测证据；
        // 只有调用方显式提供非空值时才替换现有绑定。
        String nextFixRelease = normalizeOptional(fixReleaseId);
        String nextEvaluation = normalizeOptional(evaluationResult);
        if (nextFixRelease == null) nextFixRelease = current.fixReleaseId();
        if (nextEvaluation == null) nextEvaluation = current.evaluationResult();
        if (target == FeedbackState.PUBLISHED) {
            requireText(nextFixRelease, "fixReleaseId");
            requireText(nextEvaluation, "evaluationResult");
        }
        FeedbackTransition audit = new FeedbackTransition(current.status(), target, actorId, role,
                reason.trim(), traceId.trim(), Instant.now());
        return current.transitioned(target, nextFixRelease, nextEvaluation, audit);
    }

    public static boolean canTransition(FeedbackState from, FeedbackState to) {
        return from != null && to != null
                && TRANSITIONS.getOrDefault(from, Map.of()).containsKey(to);
    }

    public static Set<FeedbackRole> allowedRoles(FeedbackState from, FeedbackState to) {
        return TRANSITIONS.getOrDefault(from, Map.of()).getOrDefault(to, Set.of());
    }

    private static FeedbackRole firstAllowed(Set<FeedbackRole> allowed, Set<FeedbackRole> actual) {
        for (FeedbackRole candidate : FeedbackRole.values()) {
            if (allowed.contains(candidate) && actual.contains(candidate)) return candidate;
        }
        return null;
    }

    private static Map<FeedbackState, Map<FeedbackState, Set<FeedbackRole>>> transitions() {
        EnumMap<FeedbackState, Map<FeedbackState, Set<FeedbackRole>>> table = new EnumMap<>(FeedbackState.class);
        table.put(FeedbackState.SUBMITTED, edge(FeedbackState.TRIAGED, FeedbackRole.OPERATOR, FeedbackRole.QUALITY));
        table.put(FeedbackState.TRIAGED, edge(FeedbackState.IN_REVIEW, FeedbackRole.OPERATOR, FeedbackRole.QUALITY));
        table.put(FeedbackState.IN_REVIEW, Map.of(
                FeedbackState.APPROVED, EnumSet.of(FeedbackRole.REVIEWER, FeedbackRole.QUALITY),
                FeedbackState.REJECTED, EnumSet.of(FeedbackRole.REVIEWER, FeedbackRole.QUALITY)));
        table.put(FeedbackState.APPROVED, edge(FeedbackState.PUBLISHED, FeedbackRole.PUBLISHER));
        table.put(FeedbackState.PUBLISHED, edge(FeedbackState.VERIFIED, FeedbackRole.OPERATOR, FeedbackRole.QUALITY));
        table.put(FeedbackState.VERIFIED, edge(FeedbackState.CLOSED, FeedbackRole.OPERATOR, FeedbackRole.QUALITY));
        table.put(FeedbackState.REJECTED, edge(FeedbackState.CLOSED, FeedbackRole.OPERATOR, FeedbackRole.QUALITY));
        return Map.copyOf(table);
    }

    private static Map<FeedbackState, Set<FeedbackRole>> edge(FeedbackState target, FeedbackRole... roles) {
        if (roles == null || roles.length == 0) throw new IllegalArgumentException("状态迁移至少需要一个角色");
        EnumSet<FeedbackRole> allowed = EnumSet.noneOf(FeedbackRole.class);
        java.util.Collections.addAll(allowed, roles);
        return Map.of(target, Set.copyOf(allowed));
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
    }

    /** 带 HTTP 语义的业务异常，Controller 可映射为 403/409/400。 */
    public static class FeedbackTransitionException extends RuntimeException {
        private final int status;

        public FeedbackTransitionException(String message, int status) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
