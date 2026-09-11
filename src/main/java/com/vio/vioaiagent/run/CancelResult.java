package com.vio.vioaiagent.run;

/** cancel 的幂等结果；重复 cancel 返回相同 Run 终态而不是重复事件。 */
public record CancelResult(String runId, AgentRunStatus status, boolean changed, long terminalEventId) {
}

