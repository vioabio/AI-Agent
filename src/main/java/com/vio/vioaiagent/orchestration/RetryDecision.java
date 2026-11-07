package com.vio.vioaiagent.orchestration;

/**
 * 重试决策 — 评估一次失败后是否应该重试.
 *
 * @param action   RETRY 或 FAIL
 * @param delayMs  重试前等待毫秒数（仅 RETRY 时有效）
 * @author vio
 */
public record RetryDecision(Action action, long delayMs) {

    public enum Action { RETRY, FAIL }

    public static RetryDecision retry(long delayMs) { return new RetryDecision(Action.RETRY, delayMs); }
    public static RetryDecision fail() { return new RetryDecision(Action.FAIL, 0); }
}
