package com.vio.vioaiagent.run;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** N4 Run 版本血缘字段与 v1.0 构造器兼容性测试。 */
class AgentRunLineageTest {

    @Test
    void oldFactoriesRemainCompatibleWithoutUnknownPlaceholders() {
        AgentRun run = AgentRun.create("run-old", "session-old", "conversation-old", "user-old", "问题",
                "pokemon-scarlet-violet", "casual-singles", "rules-v1", "zh-CN", "trace-old", null,
                Instant.now().plusSeconds(60));

        assertNull(run.dlcId());
        assertNull(run.dataReleaseId());
        assertNull(run.knowledgeReleaseId());
        assertNull(run.indexReleaseId());
        assertEquals("v1", run.contractVersion());
    }

    @Test
    void fullFactoryFreezesCanonicalLineage() {
        Instant deadline = Instant.now().plusSeconds(60);
        AgentRun run = AgentRun.create("run-lineage", "session-lineage", "conversation-lineage", "user-lineage",
                "队伍建议", "pokemon-scarlet-violet", "casual-singles", "rules-v1", "zh-CN",
                "the-hidden-treasure-of-area-zero", "data-v1", "knowledge-v1", "index-v1", "v2",
                "trace-lineage", "idem-lineage", deadline);

        assertEquals("the-hidden-treasure-of-area-zero", run.dlcId());
        assertEquals("data-v1", run.dataReleaseId());
        assertEquals("knowledge-v1", run.knowledgeReleaseId());
        assertEquals("index-v1", run.indexReleaseId());
        assertEquals("v2", run.contractVersion());

        AgentRun restored = AgentRun.restore(run.id(), run.sessionId(), run.conversationId(), run.userId(),
                run.prompt(), run.gameReleaseId(), run.battleFormat(), run.rulesetVersionId(), run.language(),
                run.dlcId(), run.dataReleaseId(), run.knowledgeReleaseId(), run.indexReleaseId(),
                run.contractVersion(), run.traceId(), run.idempotencyKey(), run.createdAt(), run.deadlineAt(),
                run.status(), run.startedAt(), run.finishedAt(), 3L);
        assertEquals(3L, restored.version());
        assertEquals(run.dataReleaseId(), restored.dataReleaseId());
    }

    @Test
    void commandNormalizesUnknownReleaseIds() {
        CreateRunCommand command = new CreateRunCommand("u", null, null, "问题", "game", "singles", "rules",
                "zh-CN", "dlc", "unknown", " ", "index", "unknown", "trace", null, Duration.ofSeconds(1));

        assertNull(command.dataReleaseId());
        assertNull(command.knowledgeReleaseId());
        assertEquals("v1", command.contractVersion());
    }
}
