package com.vio.vioaiagent.rag.retrieval;

import com.vio.vioaiagent.rag.catalog.KnowledgeCatalog;

/** 双路召回并经 RRF 融合后的证据。 */
public record RetrievedKnowledgeChunk(
        KnowledgeCatalog.KnowledgeChunkView view,
        int lexicalRank,
        int semanticRank,
        double retrievalScore) {
}

