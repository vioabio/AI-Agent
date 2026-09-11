package com.vio.vioaiagent.run;

import com.vio.vioaiagent.rag.contract.AnswerContract;
import com.vio.vioaiagent.rag.contract.Citation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M2/M3 接线回归：结构化回答必须落到 Run 事件和可重放游标。 */
class PokemonAgentRunServiceContractTest {

    @Test
    void structuredAnswerIsPublishedAsGroundedRunEvents() throws Exception {
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventRepository events = new InMemoryEventRepository();
        InMemoryConversationRepository conversations = new InMemoryConversationRepository();
        InMemoryChatMessageRepository messages = new InMemoryChatMessageRepository();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ContractAwareRunExecutor executor = new ContractAwareRunExecutor() {
            @Override
            public AnswerContract executeContract(AgentRunContext context) {
                return AnswerContract.answered("依据官方资料回答", "sv-1", "singles", "rules-1",
                        List.of(new Citation("doc-1", "v3", "官方规则",
                                "https://example.invalid/rules", "chunk-1", "证据原文", 0.91)),
                        List.of("仅适用于冻结版本"), context.run().traceId());
            }

            @Override
            public String execute(AgentRunContext context) {
                return executeContract(context).answer();
            }

            @Override
            public boolean usesKnowledgeGrounding() {
                return true;
            }
        };
        PokemonAgentRunService service = new PokemonAgentRunService(runs, events, conversations,
                messages, executor, worker, scheduler);
        try {
            RunView created = service.createRun(new CreateRunCommand("user-1", "session-1", null,
                    "如何准备？", "sv-1", "singles", "rules-1", "zh-CN", "trace-1", null,
                    Duration.ofSeconds(10)));

            AgentRunStatus status = awaitTerminal(service, created.runId());
            assertEquals(AgentRunStatus.COMPLETED, status);
            List<AgentEvent> stream = service.events(created.runId(), "user-1", 0).events();
            List<AgentEventType> types = stream.stream().map(AgentEvent::type).toList();
            assertTrue(types.contains(AgentEventType.RETRIEVAL_STARTED));
            assertTrue(types.contains(AgentEventType.RETRIEVAL_FINISHED));
            assertTrue(types.contains(AgentEventType.CITATION));
            assertTrue(types.contains(AgentEventType.CONTEXT_CARD));
            assertTrue(types.contains(AgentEventType.ANSWER_COMPLETED));
            assertTrue(types.contains(AgentEventType.ANSWER_DELTA));
            assertEquals(1, types.stream().filter(AgentEventType::isTerminal).count());
            int terminalIndex = types.size() - 1;
            assertTrue(types.get(terminalIndex).isTerminal());
            assertFalse(types.subList(terminalIndex + 1, types.size()).stream()
                    .anyMatch(type -> type == AgentEventType.ANSWER_DELTA || type == AgentEventType.TOOL_STARTED));

            AgentEvent completedEvent = stream.get(terminalIndex);
            assertEquals("ANSWERED", completedEvent.payload().get("contractStatus"));
            assertEquals(1, completedEvent.payload().get("citationCount"));
            assertEquals("依据官方资料回答", completedEvent.payload().get("answer"));
        } finally {
            service.shutdown();
        }
    }

    private AgentRunStatus awaitTerminal(PokemonAgentRunService service, String runId)
            throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(3);
        AgentRunStatus status = AgentRunStatus.CREATED;
        while (Instant.now().isBefore(deadline)) {
            status = service.findRun(runId, "user-1").orElseThrow().status();
            if (status.isTerminal()) return status;
            Thread.sleep(10);
        }
        return status;
    }
}
