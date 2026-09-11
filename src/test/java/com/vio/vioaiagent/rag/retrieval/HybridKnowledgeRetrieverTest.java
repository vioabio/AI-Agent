package com.vio.vioaiagent.rag.retrieval;

import com.vio.vioaiagent.rag.catalog.KnowledgeCatalog;
import com.vio.vioaiagent.rag.domain.DocumentChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridKnowledgeRetrieverTest {

    @Test
    void shouldFuseDuplicateChunkByReciprocalRank() {
        KnowledgeCatalog.KnowledgeChunkView first = view("chunk-1");
        KnowledgeCatalog.KnowledgeChunkView second = view("chunk-2");

        List<RetrievedKnowledgeChunk> result = HybridKnowledgeRetriever.fuse(
                List.of(first, second), List.of(second, first), 2);

        assertEquals(2, result.size());
        assertEquals("chunk-1", result.get(0).view().chunk().id());
        assertEquals(1, result.get(0).lexicalRank());
        assertEquals(2, result.get(0).semanticRank());
        assertTrue(result.get(0).retrievalScore() > 0);
    }

    private KnowledgeCatalog.KnowledgeChunkView view(String id) {
        DocumentChunk chunk = new DocumentChunk(id, "version-1", 0, "宝可梦攻略", id,
                Map.of(), null, null, DocumentChunk.EmbeddingStatus.READY);
        return new KnowledgeCatalog.KnowledgeChunkView(chunk, "doc-1", "v1", "来源", "https://example.invalid",
                "zh-CN", "sv-1", "singles", "rules-1");
    }
}
