package com.vio.vioaiagent.run;

import java.time.Instant;

/** POST /chat/runs 返回的稳定视图。 */
public record RunView(String runId, String sessionId, String conversationId,
                      AgentRunStatus status, String eventsUrl, String traceId, Instant createdAt,
                      String gameReleaseId, String dlcId, String language, String battleFormatId,
                      String rulesetVersionId, String dataReleaseId, String knowledgeReleaseId,
                      String indexReleaseId, String contractVersion, String lineageStatus) {
    /** v1.0 兼容构造器；旧响应没有显式版本血缘时返回空字段和 v1 契约。 */
    public RunView(String runId, String sessionId, String conversationId,
                   AgentRunStatus status, String eventsUrl, String traceId, Instant createdAt) {
        this(runId, sessionId, conversationId, status, eventsUrl, traceId, createdAt,
                null, null, null, null, null, null, null, null, "v1", "PARTIAL");
    }

    public static RunView from(AgentRun run) {
        return new RunView(run.id(), run.sessionId(), run.conversationId(), run.status(),
                "/chat/runs/" + run.id() + "/events/stream", run.traceId(), run.createdAt(),
                run.gameReleaseId(), run.dlcId(), run.language(), run.battleFormat(), run.rulesetVersionId(),
                run.dataReleaseId(), run.knowledgeReleaseId(), run.indexReleaseId(), run.contractVersion(),
                lineageStatus(run));
    }

    private static String lineageStatus(AgentRun run) {
        if (run == null || blank(run.gameReleaseId()) || blank(run.battleFormat())
                || blank(run.rulesetVersionId()) || blank(run.dataReleaseId())
                || blank(run.knowledgeReleaseId()) || blank(run.indexReleaseId())) {
            return "PARTIAL";
        }
        return "COMPLETE";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank() || "unknown".equalsIgnoreCase(value.trim());
    }
}
