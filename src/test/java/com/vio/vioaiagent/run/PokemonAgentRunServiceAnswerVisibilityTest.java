package com.vio.vioaiagent.run;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 答案投影保留审计证据，但会话删除后不能继续作为用户资源返回。 */
class PokemonAgentRunServiceAnswerVisibilityTest {

    @Test
    void deletedConversationHidesAnswerProjectionFromOwner() {
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventRepository events = new InMemoryEventRepository();
        InMemoryConversationRepository conversations = new InMemoryConversationRepository();
        InMemoryChatMessageRepository messages = new InMemoryChatMessageRepository();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        PokemonAgentRunService service = new PokemonAgentRunService(
                runs, events, conversations, messages, context -> "ok", worker, scheduler);
        InMemoryAnswerProjectionRepository projections = new InMemoryAnswerProjectionRepository();
        service.setAnswerProjectionRepository(projections);

        try {
            Conversation conversation = new Conversation("conversation-answer-visibility", "user-1",
                    "答案可见性", Instant.now(), Instant.now());
            conversations.save(conversation);
            AgentRun run = AgentRun.create("run-answer-visibility", "session-answer-visibility",
                    conversation.id(), "user-1", "问题", "sv-1", "singles", "rules-1", "zh-CN",
                    "trace-answer-visibility", null, Instant.now().plusSeconds(60));
            runs.save(run);
            AnswerProjection projection = AnswerProjection.of("answer-answer-visibility", run.id(),
                    run.sessionId(), run.conversationId(), run.userId(), run.traceId(), "ANSWERED",
                    "答案", run.gameReleaseId(), run.battleFormat(), run.rulesetVersionId(),
                    "data-1", "knowledge-1", "index-1");
            projections.save(projection);

            assertTrue(service.findAnswer(projection.answerId(), "user-1").isPresent());
            service.deleteConversation(conversation.id(), "user-1");
            assertTrue(service.findAnswer(projection.answerId(), "user-1").isEmpty());
            // 直接审计读取仍保留，证明可见性门禁和 append-only 留存是两条边界。
            assertTrue(projections.findById(projection.answerId()).isPresent());
        } finally {
            service.shutdown();
        }
    }
}
