package com.vio.vioaiagent.rag.retrieval;

import java.util.List;

/** RAG 检索边界；调用方不直接依赖 PostgreSQL 或向量 SDK。 */
public interface KnowledgeRetriever {
    List<RetrievedKnowledgeChunk> retrieve(KnowledgeRetrievalQuery query);
}

