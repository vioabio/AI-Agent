package com.vio.vioaiagent.feedback;

/** 能够推动纠错工单状态的最小 RBAC 角色集合。 */
public enum FeedbackRole {
    OPERATOR,
    QUALITY,
    REVIEWER,
    PUBLISHER;

    public static FeedbackRole parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("反馈操作角色不能为空");
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("未知反馈操作角色: " + value, ex);
        }
    }
}
