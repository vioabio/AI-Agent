package com.vio.vioaiagent.feedback;

/**
 * 反馈快照发生并发版本冲突或 ID 归属冲突。
 * 继承 IllegalStateException 以兼容轻量仓储调用方，同时由 HTTP 层稳定映射为 409。
 */
public class FeedbackConflictException extends IllegalStateException {
    public FeedbackConflictException(String message) {
        super(message);
    }
}
