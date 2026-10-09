package com.vio.vioaiagent.run;

import com.vio.vioaiagent.rag.contract.AnswerContract;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Run 完成时必须把稳定 answerId 和投影血缘写入 completion 事件。 */
class PokemonAgentRunServiceAnswerProjectionTest {

    @Test
    void completionPersistsProjectionBeforePublishingTerminalEvent() throws Exception {
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventRepository events = new InMemoryEventRepository();
        InMemoryConversationRepository conversations = new InMemoryConversationRepository();
        InMemoryChatMessageRepository messages = new InMemoryChatMessageRepository();
        InMemoryAnswerProjectionRepository projections = new InMemoryAnswerProjectionRepository();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ContractAwareRunExecutor executor = new ContractAwareRunExecutor() {
            @Override
            public AnswerContract executeContract(AgentRunContext context) {
                return AnswerContract.answered("依据版本资料", "sv-1", "singles", "rules-1",
                        List.of(), List.of(), context.run().traceId());
            }

            @Override
            public String execute(AgentRunContext context) { return executeContract(context).answer(); }

            @Override
            public boolean usesKnowledgeGrounding() { return false; }

            @Override
            public AnswerLineage answerLineage(AgentRunContext context, AnswerContract contract) {
                return new AnswerLineage("dlc-1", "data-1", "knowledge-1", "index-1", "v1.1");
            }
        };
        PokemonAgentRunService service = new PokemonAgentRunService(runs, events, conversations,
                messages, executor, worker, scheduler);
        service.setAnswerProjectionRepository(projections);
        try {
            RunView created = service.createRun(new CreateRunCommand("user-1", "session-1", null,
                    "查询", "sv-1", "singles", "rules-1", "zh-CN",
                    "dlc-1", "data-1", "knowledge-1", "index-1", "v1.1",
                    "trace-1", null, Duration.ofSeconds(10)));
            assertEquals("data-1", created.dataReleaseId());
            assertEquals("v1.1", created.contractVersion());
            assertEquals("COMPLETE", created.lineageStatus());

            // 等待终态事件本身，而不是只等状态：服务端先落库状态、再追加 RUN_COMPLETED，
            // 只轮询状态会偶发地在事件写入前就继续执行（见 RunTestAwait 说明）。
            AgentEvent completed = RunTestAwait.awaitTerminalEvent(events, created.runId());
            assertEquals(AgentEventType.RUN_COMPLETED, completed.type());

            AnswerProjection projection = projections.findByRunId(created.runId()).orElseThrow();
            assertEquals("answer-" + created.runId(), projection.answerId());
            assertEquals(AnswerProjection.LineageStatus.COMPLETE, projection.lineageStatus());
            assertEquals("data-1", projection.dataReleaseId());
            assertEquals(projection.answerId(), completed.payload().get("answerId"));
            assertEquals(projection.lineageStatus().name(), completed.payload().get("lineageStatus"));
            assertEquals("data-1", completed.payload().get("dataReleaseId"));
            assertTrue(events.findAfter(created.runId(), 0).stream()
                    .allMatch(item -> "v1.1".equals(item.payload().get("contractVersion"))));
            assertTrue(messages.findByConversationForUser(created.conversationId(), "user-1").stream()
                    .allMatch(message -> "index-1".equals(message.metadata().get("indexReleaseId"))));
        } finally {
            service.shutdown();
        }
    }
}
