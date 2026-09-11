package com.vio.vioaiagent.run;

/**
 * 可选的答案发布血缘。N4 的领域执行器可在检索完成后提供 DLC、数据、知识和
 * 索引 release；迁移期间缺失字段保持 null，绝不使用 unknown 占位。
 */
public record AnswerLineage(
        String dlcId,
        String dataReleaseId,
        String knowledgeReleaseId,
        String indexReleaseId,
        String contractVersion) {

    public AnswerLineage {
        dlcId = normalize(dlcId);
        dataReleaseId = normalize(dataReleaseId);
        knowledgeReleaseId = normalize(knowledgeReleaseId);
        indexReleaseId = normalize(indexReleaseId);
        contractVersion = normalize(contractVersion);
        if (contractVersion == null) contractVersion = "v1";
    }

    public static AnswerLineage empty() {
        return new AnswerLineage(null, null, null, null, "v1");
    }

    public boolean hasReleaseLineage() {
        return dataReleaseId != null && knowledgeReleaseId != null && indexReleaseId != null;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() || "unknown".equalsIgnoreCase(value.trim())
                ? null : value.trim();
    }
}
