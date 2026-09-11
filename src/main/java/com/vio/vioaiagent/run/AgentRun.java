package com.vio.vioaiagent.run;

import java.time.Instant;

/** Run 聚合根；所有状态变更必须通过 synchronized transition/cancel/timeout。 */
public final class AgentRun {
    private final String id;
    private final String sessionId;
    private final String conversationId;
    private final String userId;
    private final String prompt;
    private final String gameReleaseId;
    private final String battleFormat;
    private final String rulesetVersionId;
    private final String language;
    /** 版本化领域上下文；缺失时保持 null，绝不使用 unknown 占位。 */
    private final String dlcId;
    private final String dataReleaseId;
    private final String knowledgeReleaseId;
    private final String indexReleaseId;
    private final String contractVersion;
    private final String traceId;
    private final String idempotencyKey;
    private final Instant createdAt;
    private final Instant deadlineAt;
    private volatile AgentRunStatus status;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    /**
     * 乐观锁版本。版本由仓储在成功持久化后推进，聚合本身不在状态转换时
     * 预先递增，避免一次未落库的转换被误认为已经提交。
     */
    private volatile long version;

    private AgentRun(String id, String sessionId, String conversationId, String userId, String prompt,
                     String gameReleaseId, String battleFormat, String rulesetVersionId, String language,
                     String dlcId, String dataReleaseId, String knowledgeReleaseId, String indexReleaseId,
                     String contractVersion, String traceId, String idempotencyKey,
                     Instant createdAt, Instant deadlineAt) {
        this.id = text(id, "id");
        this.sessionId = text(sessionId, "sessionId");
        this.conversationId = text(conversationId, "conversationId");
        this.userId = text(userId, "userId");
        this.prompt = text(prompt, "prompt");
        this.gameReleaseId = gameReleaseId;
        this.battleFormat = battleFormat;
        this.rulesetVersionId = rulesetVersionId;
        this.language = language;
        this.dlcId = optional(dlcId);
        this.dataReleaseId = optional(dataReleaseId);
        this.knowledgeReleaseId = optional(knowledgeReleaseId);
        this.indexReleaseId = optional(indexReleaseId);
        this.contractVersion = contractVersion(contractVersion);
        this.traceId = text(traceId, "traceId");
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.deadlineAt = deadlineAt;
        this.status = AgentRunStatus.CREATED;
        this.version = 0L;
    }

    public static AgentRun create(String id, String sessionId, String conversationId, String userId, String prompt,
                                  String gameReleaseId, String battleFormat, String rulesetVersionId, String language,
                                  String traceId, String idempotencyKey, Instant deadlineAt) {
        return create(id, sessionId, conversationId, userId, prompt, gameReleaseId, battleFormat,
                rulesetVersionId, language, null, null, null, null, "v1", traceId, idempotencyKey, deadlineAt);
    }

    /**
     * 创建带版本血缘上下文的 Run。旧工厂保留，避免升级时破坏嵌入式调用方。
     */
    public static AgentRun create(String id, String sessionId, String conversationId, String userId, String prompt,
                                  String gameReleaseId, String battleFormat, String rulesetVersionId, String language,
                                  String dlcId, String dataReleaseId, String knowledgeReleaseId, String indexReleaseId,
                                  String contractVersion, String traceId, String idempotencyKey, Instant deadlineAt) {
        return new AgentRun(id, sessionId, conversationId, userId, prompt, gameReleaseId, battleFormat,
                rulesetVersionId, language, dlcId, dataReleaseId, knowledgeReleaseId, indexReleaseId,
                contractVersion, traceId, idempotencyKey, Instant.now(), deadlineAt);
    }

    /**
     * 从持久化层恢复 Run。状态机仍然只允许通过 transitionTo/cancel 等方法继续推进，
     * 该工厂只负责还原已经落库的快照，避免 JDBC 仓储绕过聚合根私有状态。
     */
    public static AgentRun restore(String id, String sessionId, String conversationId, String userId, String prompt,
                                   String gameReleaseId, String battleFormat, String rulesetVersionId, String language,
                                   String traceId, String idempotencyKey, Instant createdAt, Instant deadlineAt,
                                   AgentRunStatus status, Instant startedAt, Instant finishedAt) {
        return restore(id, sessionId, conversationId, userId, prompt, gameReleaseId, battleFormat,
                rulesetVersionId, language, null, null, null, null, "v1", traceId, idempotencyKey, createdAt, deadlineAt,
                status, startedAt, finishedAt, 0L);
    }

    /** 从持久化层恢复带版本血缘上下文的 Run（无显式版本号时按 0 恢复）。 */
    public static AgentRun restore(String id, String sessionId, String conversationId, String userId, String prompt,
                                   String gameReleaseId, String battleFormat, String rulesetVersionId, String language,
                                   String dlcId, String dataReleaseId, String knowledgeReleaseId, String indexReleaseId,
                                   String contractVersion, String traceId, String idempotencyKey, Instant createdAt,
                                   Instant deadlineAt, AgentRunStatus status, Instant startedAt, Instant finishedAt) {
        return restore(id, sessionId, conversationId, userId, prompt, gameReleaseId, battleFormat,
                rulesetVersionId, language, dlcId, dataReleaseId, knowledgeReleaseId, indexReleaseId,
                contractVersion, traceId, idempotencyKey, createdAt, deadlineAt,
                status, startedAt, finishedAt, 0L);
    }

    /** 从持久化快照恢复（包含乐观锁版本）。 */
    public static AgentRun restore(String id, String sessionId, String conversationId, String userId, String prompt,
                                   String gameReleaseId, String battleFormat, String rulesetVersionId, String language,
                                   String traceId, String idempotencyKey, Instant createdAt, Instant deadlineAt,
                                   AgentRunStatus status, Instant startedAt, Instant finishedAt, long version) {
        return restore(id, sessionId, conversationId, userId, prompt, gameReleaseId, battleFormat,
                rulesetVersionId, language, null, null, null, null, "v1", traceId, idempotencyKey,
                createdAt, deadlineAt, status, startedAt, finishedAt, version);
    }

    /** 从持久化快照恢复带版本血缘上下文的 Run（包含乐观锁版本）。 */
    public static AgentRun restore(String id, String sessionId, String conversationId, String userId, String prompt,
                                   String gameReleaseId, String battleFormat, String rulesetVersionId, String language,
                                   String dlcId, String dataReleaseId, String knowledgeReleaseId, String indexReleaseId,
                                   String contractVersion, String traceId, String idempotencyKey, Instant createdAt,
                                   Instant deadlineAt, AgentRunStatus status, Instant startedAt, Instant finishedAt,
                                   long version) {
        AgentRun run = new AgentRun(id, sessionId, conversationId, userId, prompt, gameReleaseId, battleFormat,
                rulesetVersionId, language, dlcId, dataReleaseId, knowledgeReleaseId, indexReleaseId,
                contractVersion, traceId, idempotencyKey, createdAt, deadlineAt);
        run.status = status == null ? AgentRunStatus.CREATED : status;
        run.startedAt = startedAt;
        run.finishedAt = finishedAt;
        if (version < 0) throw new IllegalArgumentException("Run version 不能为负数");
        run.version = version;
        return run;
    }

    public synchronized boolean transitionTo(AgentRunStatus target) {
        if (!status.canTransitionTo(target)) return false;
        status = target;
        if (target == AgentRunStatus.RUNNING && startedAt == null) startedAt = Instant.now();
        if (target.isTerminal()) finishedAt = Instant.now();
        return true;
    }

    public synchronized boolean cancel() { return transitionTo(AgentRunStatus.CANCELLED); }
    public synchronized boolean timeout() { return transitionTo(AgentRunStatus.TIMED_OUT); }
    public synchronized boolean fail() { return transitionTo(AgentRunStatus.FAILED); }
    public synchronized boolean complete() { return transitionTo(AgentRunStatus.COMPLETED); }

    public String id() { return id; }
    public String sessionId() { return sessionId; }
    public String conversationId() { return conversationId; }
    public String userId() { return userId; }
    public String prompt() { return prompt; }
    public String gameReleaseId() { return gameReleaseId; }
    public String battleFormat() { return battleFormat; }
    public String rulesetVersionId() { return rulesetVersionId; }
    public String language() { return language; }
    public String dlcId() { return dlcId; }
    public String dataReleaseId() { return dataReleaseId; }
    public String knowledgeReleaseId() { return knowledgeReleaseId; }
    public String indexReleaseId() { return indexReleaseId; }
    public String contractVersion() { return contractVersion; }
    public String traceId() { return traceId; }
    public String idempotencyKey() { return idempotencyKey; }
    public Instant createdAt() { return createdAt; }
    public Instant deadlineAt() { return deadlineAt; }
    public AgentRunStatus status() { return status; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
    public long version() { return version; }

    /**
     * 仅供同包仓储在 CAS 成功后更新版本；业务代码不能自行伪造版本。
     */
    synchronized void markPersistedVersion(long persistedVersion) {
        if (persistedVersion < version) {
            throw new IllegalStateException("Run version 不能回退: " + version + " -> " + persistedVersion);
        }
        version = persistedVersion;
    }

    private static String text(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value;
    }

    private static String optional(String value) {
        if (value == null || value.isBlank() || "unknown".equalsIgnoreCase(value.trim())) return null;
        return value.trim();
    }

    private static String contractVersion(String value) {
        String normalized = optional(value);
        return normalized == null ? "v1" : normalized;
    }
}
