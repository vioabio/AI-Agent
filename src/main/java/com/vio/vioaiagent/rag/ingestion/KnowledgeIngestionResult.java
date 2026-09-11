package com.vio.vioaiagent.rag.ingestion;

import com.vio.vioaiagent.rag.domain.DocumentChunk;
import com.vio.vioaiagent.rag.domain.DocumentVersion;
import com.vio.vioaiagent.rag.domain.IngestionBatch;

import java.util.List;

/** 摄取结果，包含批次、版本和切块摘要，不包含原始密钥或隐私内容。 */
public record KnowledgeIngestionResult(
        IngestionBatch batch,
        DocumentVersion documentVersion,
        List<DocumentChunk> chunks,
        List<String> warnings) {

    public KnowledgeIngestionResult {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}

