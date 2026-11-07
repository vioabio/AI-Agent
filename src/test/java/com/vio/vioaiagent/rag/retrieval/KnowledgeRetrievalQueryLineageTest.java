package com.vio.vioaiagent.rag.retrieval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KnowledgeRetrievalQueryLineageTest {
    @Test
    void carriesReleaseKeysIntoCatalogFilter() {
        KnowledgeRetrievalQuery query = new KnowledgeRetrievalQuery("属性", "zh-CN",
                "pokemon-scarlet-violet", "casual-singles", "casual-v1", 5,
                "data-v1", "knowledge-v1", "index-v1");
        assertEquals("data-v1", query.filter().dataReleaseId());
        assertEquals("knowledge-v1", query.filter().knowledgeReleaseId());
        assertEquals("index-v1", query.filter().indexReleaseId());
    }

    @Test
    void oldConstructorLeavesReleaseKeysUnboundedForCompatibility() {
        KnowledgeRetrievalQuery query = new KnowledgeRetrievalQuery("属性", "zh-CN",
                "sv-1", "singles", "rules-1", 5);
        assertEquals(null, query.filter().dataReleaseId());
        assertEquals(null, query.filter().knowledgeReleaseId());
        assertEquals(null, query.filter().indexReleaseId());
    }
}
