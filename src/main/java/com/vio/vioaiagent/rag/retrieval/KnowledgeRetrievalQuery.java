package com.vio.vioaiagent.rag.retrieval;

import com.vio.vioaiagent.rag.catalog.KnowledgeCatalog;

/** 带版本边界的检索请求。缺少语言或游戏版本时由上层追问，不进行无界检索。 */
public record KnowledgeRetrievalQuery(
        String text,
        String language,
        String gameReleaseId,
        String battleFormatId,
        String rulesetVersionId,
        int topK,
        String dataReleaseId,
        String knowledgeReleaseId,
        String indexReleaseId) {

    /** v1.0 兼容构造器：旧调用方没有发布血缘时保持显式的空过滤。 */
    public KnowledgeRetrievalQuery(String text, String language, String gameReleaseId,
                                   String battleFormatId, String rulesetVersionId, int topK) {
        this(text, language, gameReleaseId, battleFormatId, rulesetVersionId, topK,
                null, null, null);
    }

    public KnowledgeRetrievalQuery {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("检索文本不能为空");
        }
        language = normalize(language);
        gameReleaseId = normalize(gameReleaseId);
        battleFormatId = normalize(battleFormatId);
        rulesetVersionId = normalize(rulesetVersionId);
        dataReleaseId = normalize(dataReleaseId);
        knowledgeReleaseId = normalize(knowledgeReleaseId);
        indexReleaseId = normalize(indexReleaseId);
        topK = topK <= 0 ? 5 : Math.min(topK, 50);
    }

    public KnowledgeCatalog.KnowledgeFilter filter() {
        return new KnowledgeCatalog.KnowledgeFilter(language, gameReleaseId, battleFormatId, rulesetVersionId,
                dataReleaseId, knowledgeReleaseId, indexReleaseId);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
