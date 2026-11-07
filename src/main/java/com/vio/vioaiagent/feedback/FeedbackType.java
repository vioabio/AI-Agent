package com.vio.vioaiagent.feedback;

/** 用户反馈类型；标签值保持有限基数，避免污染指标。 */
public enum FeedbackType {
    HELPFUL,
    NOT_HELPFUL,
    CORRECTION;

    public static FeedbackType parse(String value) {
        if (value == null || value.isBlank()) {
            return CORRECTION;
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT)
                .replace('-', '_');
        if ("LIKE".equals(normalized)) return HELPFUL;
        if ("DISLIKE".equals(normalized) || "UNHELPFUL".equals(normalized)) return NOT_HELPFUL;
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("未知反馈类型: " + value, ex);
        }
    }
}
