package com.vio.vioaiagent.orchestration;

/**
 * 工具执行超时异常.
 *
 * @author vio
 */
public class ToolTimeoutException extends RuntimeException {
    public ToolTimeoutException(String message) { super(message); }
    public ToolTimeoutException(String message, Throwable cause) { super(message, cause); }
}
