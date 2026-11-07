package com.vio.vioaiagent.feedback;

/** 反馈资源不存在，避免向调用方泄露其他用户的工单是否存在。 */
public class FeedbackNotFoundException extends RuntimeException {
    public FeedbackNotFoundException(String feedbackId) {
        super("反馈不存在: " + feedbackId);
    }
}
