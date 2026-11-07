package com.vio.vioaiagent.run;

import com.vio.vioaiagent.rag.contract.AnswerContract;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PokemonAgentRunServiceStreamingTest {
    @Test
    void secondTurnReceivesStoredHistoryFromTheSameConversation() {
        Fixture f = new Fixture(context -> {
            String text;
            if (context.history().isEmpty()) text = "杰尼龟";
            else {
                assertEquals(List.of("你好", "杰尼龟"), context.history().stream().map(m -> m.getText()).toList());
                text = "水属性";
            }
            context.answerDeltaSink().accept(text);
            return answer(context, text);
        });
        try {
            var first = f.create();
            assertEquals(AgentEventType.RUN_COMPLETED, RunTestAwait.awaitTerminalEvent(f.events, first.runId()).type());
            var second = f.service.createRun(new CreateRunCommand("local-user", null, first.conversationId(),
                    "它是什么属性", "sv-1", "singles", "rules-1", "zh-CN", null, null, Duration.ofSeconds(30)));
            assertEquals(AgentEventType.RUN_COMPLETED, RunTestAwait.awaitTerminalEvent(f.events, second.runId()).type());
            assertEquals("水属性", f.events.findAfter(second.runId(), 0).stream()
                    .filter(e -> e.type() == AgentEventType.ANSWER_DELTA).findFirst().orElseThrow().payload().get("text"));
        } finally { f.service.shutdown(); }
    }
    @Test
    void cancelledRunRejectsLateChunksEvenWhenProviderIgnoresInterruption() throws Exception {
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch lateAttempt = new CountDownLatch(1);
        Fixture f = new Fixture(context -> {
            context.answerDeltaSink().accept("前半段");
            first.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (release.getCount() != 0 && System.nanoTime() < deadline) {
                try { release.await(100, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) { /* simulate a stubborn provider */ }
            }
            try { context.answerDeltaSink().accept("不应显示"); }
            finally { lateAttempt.countDown(); }
            return answer(context, "前半段不应显示");
        });
        try {
            var run = f.create();
            assertTrue(first.await(5, TimeUnit.SECONDS));
            f.service.cancel(run.runId(), "local-user");
            release.countDown();
            assertTrue(lateAttempt.await(5, TimeUnit.SECONDS));
            assertEquals(AgentEventType.RUN_CANCELLED, RunTestAwait.awaitTerminalEvent(f.events, run.runId()).type());
            assertEquals(List.of("前半段"), f.events.findAfter(run.runId(), 0).stream()
                    .filter(e -> e.type() == AgentEventType.ANSWER_DELTA)
                    .map(e -> (String) e.payload().get("text")).toList());
        } finally { release.countDown(); f.service.shutdown(); }
    }
    @Test
    void firstDeltaIsReadableBeforeCompletionAndFinalAnswerIsNotAppendedTwice() throws Exception {
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        Fixture f = new Fixture(context -> {
            context.answerDeltaSink().accept("你好，");
            first.countDown();
            assertTrue(finish.await(5, TimeUnit.SECONDS));
            context.answerDeltaSink().accept("杰尼龟！");
            return answer(context, "你好，杰尼龟！");
        });
        try {
            var run = f.create();
            assertTrue(first.await(5, TimeUnit.SECONDS));
            assertEquals(AgentRunStatus.RUNNING, f.runs.findById(run.runId()).orElseThrow().status());
            assertEquals(List.of("你好，"), f.events.findAfter(run.runId(), 0).stream()
                    .filter(e -> e.type() == AgentEventType.ANSWER_DELTA)
                    .map(e -> (String) e.payload().get("text")).toList());
            assertTrue(f.messages.findByConversationForUser(run.conversationId(), "local-user").stream()
                    .noneMatch(m -> m.role().equals("assistant")));
            finish.countDown();
            assertEquals(AgentEventType.RUN_COMPLETED, RunTestAwait.awaitTerminalEvent(f.events, run.runId()).type());
            assertEquals(2, f.events.findAfter(run.runId(), 0).stream()
                    .filter(e -> e.type() == AgentEventType.ANSWER_DELTA).count());
        } finally { finish.countDown(); f.service.shutdown(); }
    }

    @Test
    void divergentFinalAnswerFailsInsteadOfPublishingCompletedFact() {
        Fixture f = new Fixture(context -> {
            context.answerDeltaSink().accept("部分回答");
            return answer(context, "不一致的答案");
        });
        try {
            var run = f.create();
            assertEquals(AgentEventType.RUN_FAILED, RunTestAwait.awaitTerminalEvent(f.events, run.runId()).type());
            assertTrue(f.events.findAfter(run.runId(), 0).stream()
                    .noneMatch(e -> e.type() == AgentEventType.ANSWER_COMPLETED));
        } finally { f.service.shutdown(); }
    }

    private static AnswerContract answer(AgentRunContext context, String text) {
        var r = context.run();
        return AnswerContract.answered(text, r.gameReleaseId(), r.battleFormat(),
                r.rulesetVersionId(), List.of(), List.of(), r.traceId());
    }

    @FunctionalInterface
    private interface Generation { AnswerContract call(AgentRunContext context) throws Exception; }

    private static class Fixture {
        final InMemoryRunRepository runs = new InMemoryRunRepository();
        final InMemoryEventRepository events = new InMemoryEventRepository();
        final InMemoryChatMessageRepository messages = new InMemoryChatMessageRepository();
        final PokemonAgentRunService service;
        Fixture(Generation generation) {
            ContractAwareRunExecutor executor = new ContractAwareRunExecutor() {
                public boolean usesKnowledgeGrounding() { return false; }
                public AnswerContract executeContract(AgentRunContext c) throws Exception { return generation.call(c); }
                public String execute(AgentRunContext c) throws Exception { return executeContract(c).answer(); }
            };
            service = new PokemonAgentRunService(runs, events, new InMemoryConversationRepository(),
                    messages, executor, Executors.newSingleThreadExecutor(), Executors.newSingleThreadScheduledExecutor());
            ReflectionTestUtils.setField(service, "answerStreamingEnabled", true);
        }
        RunView create() {
            return service.createRun(new CreateRunCommand("local-user", null, null, "你好",
                    "sv-1", "singles", "rules-1", "zh-CN", null, null, Duration.ofSeconds(30)));
        }
    }
}
