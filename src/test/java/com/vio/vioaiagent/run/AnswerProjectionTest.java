package com.vio.vioaiagent.run;

import com.vio.vioaiagent.rag.contract.AnswerContract;
import com.vio.vioaiagent.rag.contract.Citation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnswerProjectionTest {

    @Test
    void projectionIsStableAndMarksMissingReleaseLineageExplicitly() {
        AgentRun run = AgentRun.create("run-answer-1", "session-1", "conversation-1", "user-1",
                "问题", "sv-1", "singles", "rules-1", "zh-CN", "trace-1", null,
                Instant.now().plusSeconds(30));
        AnswerContract contract = AnswerContract.answered("答案", "sv-1", "singles", "rules-1",
                List.of(new Citation("doc-1", null, "来源", "https://example.invalid", "chunk-1", "证据", 0.9)),
                List.of("限制"), "trace-1");

        AnswerProjection projection = AnswerProjection.from(run, contract, "answer-run-answer-1",
                Instant.now());

        assertEquals("answer-run-answer-1", projection.answerId());
        assertEquals("run-answer-1", projection.runId());
        assertEquals(AnswerProjection.LineageStatus.PARTIAL, projection.lineageStatus());
        assertFalse(projection.lineageComplete());
        assertEquals("答案", projection.answer());
        assertEquals(1, projection.citations().size());
        assertEquals("", projection.lineagePayload().get("dataReleaseId"));
        assertTrue(projection.answerDigest().matches("[0-9a-f]{64}"));
    }

    @Test
    void repositoryRejectsConflictingReplayAndEnforcesUserScope() {
        InMemoryAnswerProjectionRepository repository = new InMemoryAnswerProjectionRepository();
        AnswerProjection value = AnswerProjection.of("answer-1", "run-1", "session-1", "conversation-1",
                "user-1", "trace-1", "ANSWERED", "答案", "sv-1", "singles", "rules-1",
                "data-1", "knowledge-1", "index-1");

        assertEquals(value, repository.save(value));
        assertEquals(value, repository.save(value));
        assertTrue(repository.findByIdForUser("answer-1", "user-1").isPresent());
        assertTrue(repository.findByIdForUser("answer-1", "user-2").isEmpty());
        AnswerProjection conflicting = new AnswerProjection(value.answerId(), value.runId(), value.sessionId(),
                value.conversationId(), value.userId(), value.traceId(), value.status(), "另一个答案",
                value.gameReleaseId(), value.dlcId(), value.battleFormatId(), value.rulesetVersionId(),
                value.dataReleaseId(), value.knowledgeReleaseId(), value.indexReleaseId(), value.contractVersion(),
                value.lineageStatus(), null, List.<Map<String, Object>>of(), List.of(), value.createdAt(),
                value.completedAt());
        assertThrows(IllegalStateException.class, () -> repository.save(conflicting));
    }

    @Test
    void completeLineageRequiresAllReleaseKeys() {
        AnswerProjection value = new AnswerProjection("answer-2", "run-2", "session-2", "conversation-2",
                "user-2", "trace-2", "ANSWERED", "ok", "sv-1", "dlc-1", "singles", "rules-1",
                "data-1", "knowledge-1", "index-1", "v1", null, null, List.of(), List.of(),
                Instant.now(), Instant.now());
        assertEquals(AnswerProjection.LineageStatus.COMPLETE, value.lineageStatus());
        assertTrue(value.lineageComplete());
    }

    @Test
    void projectionFallsBackToLineageFrozenOnRun() {
        AgentRun run = AgentRun.create("run-answer-3", "session-3", "conversation-3", "user-3",
                "问题", "sv-1", "singles", "rules-1", "zh-CN", "dlc-1",
                "data-1", "knowledge-1", "index-1", "v1.1", "trace-3", null,
                Instant.now().plusSeconds(30));
        AnswerContract contract = AnswerContract.answered("答案", "sv-1", "singles", "rules-1",
                List.of(), List.of(), "trace-3");

        AnswerProjection projection = AnswerProjection.from(run, contract, "answer-3",
                Instant.now(), AnswerLineage.empty());

        assertEquals(AnswerProjection.LineageStatus.COMPLETE, projection.lineageStatus());
        assertEquals("data-1", projection.dataReleaseId());
        assertEquals("knowledge-1", projection.knowledgeReleaseId());
        assertEquals("index-1", projection.indexReleaseId());
        assertEquals("v1.1", projection.contractVersion());
    }
}
