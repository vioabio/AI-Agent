package com.vio.vioaiagent.feedback;

/**
 * 纠错工单状态。状态名称与 evaluation/contracts/feedback-state-machine.json 保持一致，
 * 方便评测脚本、运营后台和数据库迁移共享同一份契约。
 */
public enum FeedbackState {
    SUBMITTED,
    TRIAGED,
    IN_REVIEW,
    APPROVED,
    REJECTED,
    PUBLISHED,
    VERIFIED,
    CLOSED;

    public static FeedbackState parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("反馈状态不能为空");
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("未知反馈状态: " + value, ex);
        }
    }

    public boolean isTerminal() {
        return this == CLOSED;
    }
}
