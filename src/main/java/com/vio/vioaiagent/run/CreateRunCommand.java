package com.vio.vioaiagent.run;

import java.time.Duration;

/** 创建 Run 的内部命令；HTTP DTO 在 Controller 层转换为该契约。 */
public record CreateRunCommand(
        String userId,
        String sessionId,
        String conversationId,
        String prompt,
        String gameReleaseId,
        String battleFormat,
        String rulesetVersionId,
        String language,
        String dlcId,
        String dataReleaseId,
        String knowledgeReleaseId,
        String indexReleaseId,
        String contractVersion,
        String traceId,
        String idempotencyKey,
        Duration timeout) {

    /**
     * v1.0 兼容构造器。新字段统一使用 null/v1 默认值，调用方可逐步迁移到
     * 带完整版本血缘的构造器，而不破坏既有测试和嵌入式集成。
     */
    public CreateRunCommand(String userId, String sessionId, String conversationId, String prompt,
                            String gameReleaseId, String battleFormat, String rulesetVersionId,
                            String language, String traceId, String idempotencyKey, Duration timeout) {
        this(userId, sessionId, conversationId, prompt, gameReleaseId, battleFormat, rulesetVersionId,
                language, null, null, null, null, "v1", traceId, idempotencyKey, timeout);
    }

    public CreateRunCommand {
        userId = normalize(userId, "anonymous");
        prompt = require(prompt, "prompt");
        sessionId = normalize(sessionId, null);
        conversationId = normalize(conversationId, null);
        gameReleaseId = normalize(gameReleaseId, null);
        battleFormat = normalize(battleFormat, null);
        rulesetVersionId = normalize(rulesetVersionId, null);
        language = normalize(language, "zh-CN");
        dlcId = normalizeVersion(dlcId);
        dataReleaseId = normalizeVersion(dataReleaseId);
        knowledgeReleaseId = normalizeVersion(knowledgeReleaseId);
        indexReleaseId = normalizeVersion(indexReleaseId);
        contractVersion = normalizeVersion(contractVersion);
        if (contractVersion == null) contractVersion = "v1";
        traceId = normalize(traceId, null);
        idempotencyKey = normalize(idempotencyKey, null);
        timeout = timeout == null || timeout.isNegative() || timeout.isZero()
                ? Duration.ofMinutes(2) : timeout;
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String normalizeVersion(String value) {
        if (value == null || value.isBlank() || "unknown".equalsIgnoreCase(value.trim())) return null;
        return value.trim();
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value.trim();
    }
}
