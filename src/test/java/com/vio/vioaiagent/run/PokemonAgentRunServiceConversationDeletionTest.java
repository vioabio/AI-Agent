package com.vio.vioaiagent.run;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M3 资源删除回归：会话删除后不能通过进程内 activeRuns 重新读到 Run。 */
class PokemonAgentRunServiceConversationDeletionTest {

    @Test
    void deletingConversationEvictsActiveRunAndHidesItsResources() throws Exception {
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventRepository events = new InMemoryEventRepository();
        InMemoryConversationRepository conversations = new InMemoryConversationRepository();
        InMemoryChatMessageRepository messages = new InMemoryChatMessageRepository();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RunExecutor blockingExecutor = context -> {
            entered.countDown();
            while (release.getCount() != 0) {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    // Simulate an executor that ignores cancellation interrupts
                    // and returns only after its external operation is released.
                }
            }
            return "迟到的回答";
        };
        PokemonAgentRunService service = new PokemonAgentRunService(runs, events, conversations,
                messages, blockingExecutor, worker, scheduler);
        try {
            RunView created = service.createRun(new CreateRunCommand("user-1", "session-1", null,
                    "待删除会话", "sv-1", "singles", "rules-1", "zh-CN", "trace-1", null,
                    Duration.ofSeconds(30)));
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            service.deleteConversation(created.conversationId(), "user-1");

            assertTrue(service.findRun(created.runId(), "user-1").isEmpty());
            assertThrows(RunNotFoundException.class,
                    () -> service.events(created.runId(), "user-1", 0));
            assertThrows(RunNotFoundException.class,
                    () -> service.messages(created.conversationId(), "user-1"));
            release.countDown();
            service.shutdown();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(messages.findByConversationForUser(created.conversationId(), "user-1").isEmpty());
        } finally {
            release.countDown();
            service.shutdown();
        }
    }
}
