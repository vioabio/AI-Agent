package com.vio.vioaiagent.orchestration;

/**
 * 非法状态转换异常 — 当尝试执行不合法的会话状态转换时抛出.
 *
 * @author vio
 */
public class IllegalStateTransitionException extends RuntimeException {

    public IllegalStateTransitionException(String message) {
        super(message);
    }
}
