package com.vio.vioaiagent.rag.ingestion;

/** 摄取 Job/DLQ 不存在；HTTP 入口稳定映射为 404。 */
public class KnowledgeIngestionNotFoundException extends IllegalArgumentException {

    public KnowledgeIngestionNotFoundException(String message) {
        super(message);
    }
}
