package com.vio.vioaiagent.rag.ingestion;

/**
 * 摄取 Job 的并发/幂等状态冲突。
 *
 * <p>继承 {@link IllegalStateException} 保持旧的程序化调用兼容；HTTP 入口
 * 可以把它稳定映射为 409，而不是把业务冲突误报成参数错误。</p>
 */
public class KnowledgeIngestionConflictException extends IllegalStateException {

    public KnowledgeIngestionConflictException(String message) {
        super(message);
    }

    public KnowledgeIngestionConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
