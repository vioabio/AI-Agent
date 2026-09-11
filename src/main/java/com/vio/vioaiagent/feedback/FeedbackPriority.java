package com.vio.vioaiagent.feedback;

/** 纠错优先级；P0/P1 可在运营侧配置更短的 SLA。 */
public enum FeedbackPriority {
    P0,
    P1,
    P2,
    P3;

    public static FeedbackPriority parse(String value) {
        if (value == null || value.isBlank()) return P2;
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("未知反馈优先级: " + value, ex);
        }
    }
}
