package com.vio.vioaiagent.run;

import java.time.Instant;

/**
 * 可恢复执行的最小检查点。它记录执行位置和下一步动作，不把模型提示词或密钥写入事件。
 */
public record AgentCheckpoint(
        String runId,
        int stepNo,
        String executorVersion,
        String contextHash,
        long lastEventId,
        String nextAction,
        String leaseOwner,
        Instant leaseExpiresAt,
        Instant heartbeatAt,
        Instant updatedAt,
        long version) {

    public AgentCheckpoint {
        require(runId, "runId");
        if (stepNo < 0) throw new IllegalArgumentException("stepNo 不能为负数");
        require(executorVersion, "executorVersion");
        require(contextHash, "contextHash");
        if (lastEventId < 0) throw new IllegalArgumentException("lastEventId 不能为负数");
        require(nextAction, "nextAction");
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
        if (version < 0) throw new IllegalArgumentException("checkpoint version 不能为负数");
    }

    public static AgentCheckpoint initial(String runId, String executorVersion,
                                          String contextHash, String nextAction) {
        return new AgentCheckpoint(runId, 0, executorVersion, contextHash, 0,
                nextAction, null, null, null, Instant.now(), 0);
    }

    public AgentCheckpoint withProgress(int nextStepNo, long eventId, String nextAction,
                                        String leaseOwner, Instant leaseExpiresAt,
                                        Instant heartbeatAt) {
        return new AgentCheckpoint(runId, nextStepNo, executorVersion, contextHash,
                eventId, nextAction, leaseOwner, leaseExpiresAt, heartbeatAt,
                Instant.now(), version);
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
    }
}
