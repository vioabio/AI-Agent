package com.vio.vioaiagent.run;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M3 幂等回归：重复提交同一 Idempotency-Key 只能产生一个 Run/一次执行。 */
class PokemonAgentRunServiceIdempotencyTest {

    @Test
    void repeatedCreateWithSameKeyReusesRunAndExecutesOnce() throws Exception {
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventRepository events = new InMemoryEventRepository();
        InMemoryConversationRepository conversations = new InMemoryConversationRepository();
        InMemoryChatMessageRepository messages = new InMemoryChatMessageRepository();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger executions = new AtomicInteger();
        RunExecutor executor = context -> {
            executions.incrementAndGet();
            return "幂等结果";
        };
        PokemonAgentRunService service = new PokemonAgentRunService(runs, events, conversations,
                messages, executor, worker, scheduler);
        try {
            CreateRunCommand command = new CreateRunCommand("user-idem", "session-idem", null,
                    "重复提交", "sv-1", "singles", "rules-1", "zh-CN", "trace-idem",
                    "idem-key-1", Duration.ofSeconds(10));

            RunView first = service.createRun(command);
            RunView second = service.createRun(command);

            assertEquals(first.runId(), second.runId(), "相同幂等键必须复用原 Run");
            assertEquals(first.conversationId(), second.conversationId(), "不能创建第二个会话");
            assertTrue(awaitTerminal(service, first.runId()), "Run 应在测试超时前进入终态");
            assertEquals(1, executions.get(), "幂等重试不能再次调用执行器");
            List<AgentEvent> stream = service.events(first.runId(), "user-idem", 0).events();
            assertEquals(1, stream.stream().filter(event -> event.type().isTerminal()).count(),
                    "一个 Run 只能有一个终态事件");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void persistentIdempotencyStoreRejectsPayloadMutation() {
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventRepository events = new InMemoryEventRepository();
        InMemoryConversationRepository conversations = new InMemoryConversationRepository();
        InMemoryChatMessageRepository messages = new InMemoryChatMessageRepository();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        PokemonAgentRunService service = new PokemonAgentRunService(runs, events, conversations,
                messages, context -> "ok", worker, scheduler);
        service.setIdempotencyStore(new InMemoryIdempotencyStore());
        try {
            CreateRunCommand first = new CreateRunCommand("u1", null, null, "原始问题",
                    "sv-1", "singles", "rules-1", "zh-CN", "trace-1", "stable-key", Duration.ofSeconds(10));
            CreateRunCommand changed = new CreateRunCommand("u1", null, null, "被篡改的问题",
                    "sv-1", "singles", "rules-1", "zh-CN", "trace-1", "stable-key", Duration.ofSeconds(10));
            service.createRun(first);
            assertThrows(IdempotencyConflictException.class, () -> service.createRun(changed));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void idempotencyCompletionFailureDoesNotStrandCreatedRun() throws Exception {
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventRepository events = new InMemoryEventRepository();
        InMemoryConversationRepository conversations = new InMemoryConversationRepository();
        InMemoryChatMessageRepository messages = new InMemoryChatMessageRepository();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger executions = new AtomicInteger();
        PokemonAgentRunService service = new PokemonAgentRunService(runs, events, conversations,
                messages, context -> {
                    executions.incrementAndGet();
                    return "仍然执行";
                }, worker, scheduler);
        service.setIdempotencyStore(new IdempotencyStore() {
            @Override
            public Claim claim(String scope, String key, String requestHash,
                               Duration ttl, Instant now) {
                return Claim.acceptedClaim();
            }

            @Override
            public IdempotencyRecord complete(String scope, String key, String requestHash,
                                              int responseStatus, java.util.Map<String, Object> responseBody,
                                              Instant now) {
                throw new IllegalStateException("synthetic idempotency outage");
            }

            @Override
            public int deleteExpired(Instant now, int limit) {
                return 0;
            }
        });
        try {
            CreateRunCommand command = new CreateRunCommand("u-complete-failure", "session", null,
                    "幂等存储故障", "sv-1", "singles", "rules-1", "zh-CN", "trace", "key-failure",
                    Duration.ofSeconds(10));
            assertThrows(IllegalStateException.class, () -> service.createRun(command));

            AgentRun persisted = runs.findByIdempotencyKey("u-complete-failure", "key-failure")
                    .orElseThrow();
            Instant deadline = Instant.now().plusSeconds(3);
            while (!persisted.status().isTerminal() && Instant.now().isBefore(deadline)) {
                Thread.sleep(10);
            }
            assertEquals(AgentRunStatus.COMPLETED, persisted.status());
            assertEquals(1, executions.get());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void startupRecoveryFailsClosedForOrphanRun() {
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventRepository events = new InMemoryEventRepository();
        InMemoryConversationRepository conversations = new InMemoryConversationRepository();
        InMemoryChatMessageRepository messages = new InMemoryChatMessageRepository();
        AgentRun orphan = AgentRun.create("run-orphan", "session-orphan", "conversation-orphan",
                "user-idem", "进程中断", "sv-1", "singles", "rules-1", "zh-CN",
                "trace-orphan", null, Instant.now().plusSeconds(60));
        runs.save(orphan);
        events.appendNext(AgentEvent.of(1, AgentEventType.RUN_CREATED,
                orphan.sessionId(), orphan.id(), orphan.traceId(), java.util.Map.of()));

        ExecutorService worker = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        PokemonAgentRunService service = new PokemonAgentRunService(runs, events, conversations,
                messages, context -> "不会执行", worker, scheduler);
        try {
            service.recoverInFlightRuns();

            AgentRun recovered = runs.findById(orphan.id()).orElseThrow();
            assertEquals(AgentRunStatus.FAILED, recovered.status());
            List<AgentEvent> stream = events.findAfter(orphan.id(), 0);
            assertEquals(AgentEventType.RUN_FAILED, stream.get(stream.size() - 1).type());
            assertEquals("SERVICE_RESTARTED",
                    stream.get(stream.size() - 1).payload().get("errorType"));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void cancelReasonIsNormalizedAndPartOfIdempotencyHash() throws Exception {
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventRepository events = new InMemoryEventRepository();
        InMemoryConversationRepository conversations = new InMemoryConversationRepository();
        InMemoryChatMessageRepository messages = new InMemoryChatMessageRepository();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PokemonAgentRunService service = new PokemonAgentRunService(runs, events, conversations,
                messages, context -> {
                    started.countDown();
                    release.await(2, TimeUnit.SECONDS);
                    return "cancelled";
                }, worker, scheduler);
        service.setIdempotencyStore(new InMemoryIdempotencyStore());
        try {
            RunView created = service.createRun(new CreateRunCommand("u-cancel", "s-cancel", null,
                    "可取消任务", "sv-1", "singles", "rules-1", "zh-CN", "trace-cancel",
                    null, Duration.ofSeconds(10)));
            assertTrue(started.await(2, TimeUnit.SECONDS));

            String rawReason = "  用户主动取消\r\n token=super-secret\u0001 ";
            CancelResult first = service.cancel(created.runId(), "u-cancel", "cancel-key", rawReason);
            assertEquals(AgentRunStatus.CANCELLED, first.status());
            AgentEvent cancelled = events.findAfter(created.runId(), 0).stream()
                    .filter(event -> event.type() == AgentEventType.RUN_CANCELLED)
                    .findFirst().orElseThrow();
            String normalized = String.valueOf(cancelled.payload().get("reason"));
            assertTrue(normalized.startsWith("用户主动取消"));
            assertTrue(normalized.contains("token=[REDACTED]"));
            assertTrue(normalized.length() <= 512);
            assertTrue(normalized.chars().noneMatch(Character::isISOControl));

            CancelResult replay = service.cancel(created.runId(), "u-cancel", "cancel-key",
                    "用户主动取消\r\n token=super-secret\u0001");
            assertEquals(first, replay, "同一规范化原因应回放同一取消结果");
            assertThrows(IdempotencyConflictException.class,
                    () -> service.cancel(created.runId(), "u-cancel", "cancel-key", "另一个原因"));
        } finally {
            release.countDown();
            service.shutdown();
        }
    }

    @Test
    void legacyNoBodyCancelReplaysOnlyCompletedLegacyRecord() throws Exception {
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventRepository events = new InMemoryEventRepository();
        InMemoryConversationRepository conversations = new InMemoryConversationRepository();
        InMemoryChatMessageRepository messages = new InMemoryChatMessageRepository();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        PokemonAgentRunService service = new PokemonAgentRunService(runs, events, conversations,
                messages, context -> "unused", worker, scheduler);
        InMemoryIdempotencyStore idempotency = new InMemoryIdempotencyStore();
        service.setIdempotencyStore(idempotency);
        String userId = "u-legacy-cancel";
        String key = "legacy-cancel-key";
        Instant now = Instant.now();
        AgentRun run = AgentRun.create("run-legacy-cancel", "session-legacy-cancel",
                "conversation-legacy-cancel", userId, "旧版取消回放", "sv-1", "singles",
                "rules-1", "zh-CN", "trace-legacy-cancel", null, now.plusSeconds(60));
        conversations.save(new Conversation(run.conversationId(), userId, "旧版取消回放", now, now));
        runs.save(run);
        String scope = "run:cancel:" + userId + ":" + run.id();
        String legacyHash = sha256(scope + "|" + key);
        // 模拟 v1.0 已完成的无 body 取消记录；新版本 hash 会发生冲突，
        // 只有该精确旧 hash 才允许无 body 请求稳定回放。
        idempotency.complete(scope, key, legacyHash, 200,
                Map.of("runId", run.id(), "status", "COMPLETED", "changed", false,
                        "terminalEventId", 7L), now);
        try {
            CancelResult replay = service.cancel(run.id(), userId, key, null);
            assertEquals(new CancelResult(run.id(), AgentRunStatus.COMPLETED, false, 7L), replay);
            assertEquals(AgentRunStatus.CREATED, runs.findById(run.id()).orElseThrow().status(),
                    "旧记录回放不得改变当前 Run 状态或追加取消事件");

            // 显式 reason 是新请求语义，即使旧记录存在也必须保持 409，不能降级回放。
            assertThrows(IdempotencyConflictException.class,
                    () -> service.cancel(run.id(), userId, key, "operator requested"));

            // 旧记录仍处于处理中时也不能伪造一个取消结果；必须继续返回冲突。
            String inProgressKey = "legacy-cancel-in-progress";
            String inProgressHash = sha256(scope + "|" + inProgressKey);
            idempotency.claim(scope, inProgressKey, inProgressHash, Duration.ofHours(1), now);
            assertThrows(IdempotencyConflictException.class,
                    () -> service.cancel(run.id(), userId, inProgressKey, null));
        } finally {
            service.shutdown();
        }
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte item : digest) result.append(String.format("%02x", item));
        return result.toString();
    }

    private boolean awaitTerminal(PokemonAgentRunService service, String runId) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(3);
        while (Instant.now().isBefore(deadline)) {
            if (service.findRun(runId, "user-idem").orElseThrow().status().isTerminal()) return true;
            Thread.sleep(10);
        }
        return service.findRun(runId, "user-idem").orElseThrow().status().isTerminal();
    }
}
