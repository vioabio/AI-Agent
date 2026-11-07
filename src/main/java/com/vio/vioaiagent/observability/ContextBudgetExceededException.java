package com.vio.vioaiagent.observability;

/** Stable user-safe failure; no request text or provider body is retained. */
public final class ContextBudgetExceededException extends RuntimeException {
    public ContextBudgetExceededException(){super("本次请求超过上下文或回答长度预算，请缩小问题范围或开启新对话。");}
}
