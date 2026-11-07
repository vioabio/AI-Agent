package com.vio.vioaiagent.security;

/** 审计事实无法持久化时使用的稳定运行时异常。消息不得包含原始参数或凭据。 */
public class AuditWriteException extends RuntimeException {
    public AuditWriteException(String message, Throwable cause) {
        super(message, cause);
    }

    public AuditWriteException(String message) {
        super(message);
    }
}
