package com.vio.vioaiagent.rag.ingestion;

/** 当显式索引任务未配置生产向量存储时抛出，避免静默地把任务当作成功。 */
public class KnowledgeIndexingUnavailableException extends IllegalStateException {

    public KnowledgeIndexingUnavailableException(String message) {
        super(message);
    }
}
