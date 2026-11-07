package com.vio.vioaiagent.rag.ingestion;

/** 一次 Job 尝试的执行端口；实现不得自行改变 Job 状态。 */
@FunctionalInterface
public interface KnowledgeIngestionJobProcessor {

    KnowledgeIngestionResult process(KnowledgeIngestionJob job) throws Exception;
}
