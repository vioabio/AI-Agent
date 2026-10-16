package com.vio.vioaiagent.run;

import com.vio.vioaiagent.config.DurableRunProperties;
import com.vio.vioaiagent.security.HitlApproval;
import com.vio.vioaiagent.security.HitlAuditSink;
import com.vio.vioaiagent.security.HitlManager;
import com.vio.vioaiagent.security.InMemoryHitlApprovalRepository;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** N2 重启恢复回归：已进入执行阶段的 checkpoint 不能再次调用执行器。 */
class PokemonAgentRunServiceRecoveryTest {

    @Test
    void checkpointAfterExecuteFailsClosedWithoutCallingExecutor() {
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventRepository events = new InMemoryEventRepository();
        InMemoryConversationRepository conversations = new InMemoryConversationRepository();
        InMemoryChatMessageRepository messages = new InMemoryChatMessageRepository();
        InMemoryDurableRunStateRepository durable = new InMemoryDurableRunStateRepository();
        DurableRunProperties properties = new DurableRunProperties();
        properties.setDurableResume(true);
        AtomicInteger calls = new AtomicInteger();
        RunExecutor executor = context -> {
            calls.incrementAndGet();
            return "不应被重放";
        };

        AgentRun run = AgentRun.create("run-recovery", "session-recovery", "conversation-recovery",
                "user-recovery", "恢复测试", "sv-1", "singles", "rules-1", "zh-CN",
                "trace-recovery", null, Instant.now().plusSeconds(60));
        conversations.save(new Conversation(run.conversationId(), run.userId(), "恢复测试",
                Instant.now(), Instant.now()));
        runs.save(run);
        run.transitionTo(AgentRunStatus.RUNNING);
        runs.save(run);
        events.appendNext(AgentEvent.of(1, AgentEventType.RUN_CREATED, run.sessionId(), run.id(),
                run.traceId(), Map.of()));
        durable.save(new AgentCheckpoint(run.id(), 0, executor.getClass().getName(),
                contextHash(run), events.lastEventId(run.id()), "execute-answer",
                null, null, null, Instant.now(), 0));

        ExecutorService worker = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        PokemonAgentRunService service = new PokemonAgentRunService(runs, events, conversations,
                messages, executor, worker, scheduler, durable, properties);
        try {
            service.recoverInFlightRuns();

            AgentRun recovered = runs.findById(run.id()).orElseThrow();
            assertEquals(AgentRunStatus.FAILED, recovered.status());
            assertEquals(0, calls.get(), "已进入 execute-answer 的 Run 不得再次调用执行器");
            List<AgentEvent> stream = events.findAfter(run.id(), 0);
            AgentEvent failed = stream.get(stream.size() - 1);
            assertEquals(AgentEventType.RUN_FAILED, failed.type());
            assertEquals("RECOVERY_UNSAFE", failed.payload().get("errorType"));
            assertEquals("CHECKPOINT_AFTER_NON_REPLAYABLE_STEP", failed.payload().get("recoveryReason"));
            assertEquals(0, ((Number) failed.payload().get("checkpointStepNo")).intValue());
            assertEquals("execute-answer", failed.payload().get("checkpointNextAction"));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void dispatchCheckpointCanBeResubmitted() throws Exception {
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventRepository events = new InMemoryEventRepository();
        InMemoryConversationRepository conversations = new InMemoryConversationRepository();
        InMemoryChatMessageRepository messages = new InMemoryChatMessageRepository();
        InMemoryDurableRunStateRepository durable = new InMemoryDurableRunStateRepository();
        DurableRunProperties properties = new DurableRunProperties();
        properties.setDurableResume(true);
        AtomicInteger calls = new AtomicInteger();
        RunExecutor executor = context -> {
            calls.incrementAndGet();
            return "恢复成功";
        };

        AgentRun run = AgentRun.create("run-dispatch-recovery", "session-dispatch-recovery",
                "conversation-dispatch-recovery", "user-recovery", "dispatch 恢复",
                "sv-1", "singles", "rules-1", "zh-CN", "trace-dispatch", null,
                Instant.now().plusSeconds(60));
        conversations.save(new Conversation(run.conversationId(), run.userId(), "dispatch 恢复",
                Instant.now(), Instant.now()));
        runs.save(run);
        events.appendNext(AgentEvent.of(1, AgentEventType.RUN_CREATED, run.sessionId(), run.id(),
                run.traceId(), Map.of()));
        durable.save(new AgentCheckpoint(run.id(), 0, executor.getClass().getName(),
                contextHash(run), events.lastEventId(run.id()), "dispatch",
                null, null, null, Instant.now(), 0));

        ExecutorService worker = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        PokemonAgentRunService service = new PokemonAgentRunService(runs, events, conversations,
                messages, executor, worker, scheduler, durable, properties);
        try {
            service.recoverInFlightRuns();
            Instant deadline = Instant.now().plusSeconds(3);
            while (Instant.now().isBefore(deadline)
                    && !service.findRun(run.id(), run.userId()).orElseThrow().status().isTerminal()) {
                Thread.sleep(10);
            }
            assertEquals(AgentRunStatus.COMPLETED,
                    service.findRun(run.id(), run.userId()).orElseThrow().status());
            assertEquals(1, calls.get(), "dispatch checkpoint 只应提交一个 worker");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void pendingApprovalSurvivesRecoveryAndRebuildsExpiryWithoutExecutingTool() throws Exception {
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventRepository events = new InMemoryEventRepository();
        InMemoryConversationRepository conversations = new InMemoryConversationRepository();
        InMemoryChatMessageRepository messages = new InMemoryChatMessageRepository();
        InMemoryDurableRunStateRepository durable = new InMemoryDurableRunStateRepository();
        DurableRunProperties properties = new DurableRunProperties();
        properties.setDurableResume(true);
        AtomicInteger calls = new AtomicInteger();
        RunExecutor executor = context -> {
            calls.incrementAndGet();
            return "不应在审批恢复扫描中执行";
        };

        Instant now = Instant.now();
        AgentRun run = AgentRun.create("run-waiting-recovery", "session-waiting-recovery",
                "conversation-waiting-recovery", "user-waiting", "等待审批恢复", "sv-1",
                "singles", "rules-1", "zh-CN", "trace-waiting", null,
                // Run 自己的截止时间要显著晚于审批到期，否则会先走 RUN_TIMED_OUT，
                // 掩盖本用例真正要验证的「审批到期 → RUN_FAILED」路径。
                now.plusSeconds(120));
        conversations.save(new Conversation(run.conversationId(), run.userId(), "等待审批恢复",
                now, now));
        runs.save(run);
        run.transitionTo(AgentRunStatus.RUNNING);
        runs.save(run);
        run.transitionTo(AgentRunStatus.WAITING_APPROVAL);
        runs.save(run);

        InMemoryHitlApprovalRepository approvals = new InMemoryHitlApprovalRepository();
        HitlManager hitl = new HitlManager(HitlAuditSink.noop());
        hitl.setApprovalRepository(approvals);
        String approvalId = "4f0f1f0c-5f74-4b90-9e8b-9f6f9b2b1a01";
        // 审批生存期取一个折中值：既长于恢复扫描路径的执行时间（保证
        // recoverWaitingApproval 观察到的是 PENDING，走"重新挂接定时器"分支），
        // 又短到可以在测试内用 expirePending() 确定性推进到期。
        // 同时远早于上面 120s 的 Run 截止时间，避免先触发 RUN_TIMED_OUT。
        Instant approvalExpiry = now.plusSeconds(1);
        approvals.createOrGet(new HitlApproval(approvalId, run.userId(), run.id(), "write_file", "1.0.0",
                "digest-waiting", "MEDIUM", approvalExpiry, HitlApproval.Decision.PENDING,
                null, null, null, null, null, 0, now, now), now);
        events.appendNext(AgentEvent.of(1, AgentEventType.RUN_CREATED, run.sessionId(), run.id(),
                run.traceId(), Map.of()));
        events.appendNext(AgentEvent.of(2, AgentEventType.APPROVAL_REQUIRED, run.sessionId(), run.id(),
                run.traceId(), Map.of("approvalId", UUID.fromString(approvalId),
                        "expiresAt", approvalExpiry.toString())));
        durable.save(new AgentCheckpoint(run.id(), 1, executor.getClass().getName(),
                contextHash(run), events.lastEventId(run.id()), "commit-answer", null, null,
                null, now, 0));

        ExecutorService worker = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        PokemonAgentRunService service = new PokemonAgentRunService(runs, events, conversations,
                messages, executor, worker, scheduler, hitl, durable, properties);
        try {
            service.recoverInFlightRuns();
            assertEquals(AgentRunStatus.WAITING_APPROVAL,
                    runs.findById(run.id()).orElseThrow().status(),
                    "持久 PENDING 审批恢复后必须继续等待");
            assertEquals(0, calls.get(), "恢复扫描不能直接重放模型/工具");

            // 显式推进审批到期，并等待终态事件。
            //
            // 不等待真实调度器：让 hitl 注册的到期回调（由 recoverInFlightRuns 链路建立）
            // 真正触发 Run 收敛，而不依赖 180ms 的现实调度窗口。
            // expirePending() 内部按 Instant.now() 判断，因此循环到实际时钟越过
            // approvalExpiry 为止；这个窗口由测试自己控制，不受负载抖动影响。
            Instant settleDeadline = Instant.now().plusSeconds(10);
            while (Instant.now().isBefore(settleDeadline)
                    && events.findAfter(run.id(), 0).stream()
                        .noneMatch(event -> event.type() == AgentEventType.RUN_FAILED)) {
                hitl.expirePending();
                Thread.sleep(20);
            }

            AgentEvent terminal = RunTestAwait.awaitTerminalEvent(events, run.id());
            assertEquals(AgentEventType.RUN_FAILED, terminal.type(),
                    "恢复后应重新挂接审批到期，并在到期时以 RUN_FAILED 收敛");
            assertEquals(AgentRunStatus.FAILED, runs.findById(run.id()).orElseThrow().status(),
                    "恢复后应重新挂接审批过期定时器并安全失败");
            assertTrue(events.findAfter(run.id(), 0).stream()
                    .anyMatch(event -> event.type() == AgentEventType.RUN_FAILED));
            assertEquals(0, calls.get());
        } finally {
            service.shutdown();
            hitl.shutdown();
        }
    }

    @Test
    void decidedApprovalDoesNotRemainWaitingAfterRecovery() {
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventRepository events = new InMemoryEventRepository();
        InMemoryConversationRepository conversations = new InMemoryConversationRepository();
        InMemoryChatMessageRepository messages = new InMemoryChatMessageRepository();
        AgentRun run = AgentRun.create("run-decided-recovery", "session-decided-recovery",
                "conversation-decided-recovery", "user-decided", "已决定审批", "sv-1", "singles",
                "rules-1", "zh-CN", "trace-decided", null, Instant.now().plusSeconds(60));
        Instant now = Instant.now();
        conversations.save(new Conversation(run.conversationId(), run.userId(), "已决定审批", now, now));
        runs.save(run);
        run.transitionTo(AgentRunStatus.RUNNING);
        runs.save(run);
        run.transitionTo(AgentRunStatus.WAITING_APPROVAL);
        runs.save(run);

        InMemoryHitlApprovalRepository approvals = new InMemoryHitlApprovalRepository();
        HitlManager hitl = new HitlManager(HitlAuditSink.noop());
        hitl.setApprovalRepository(approvals);
        String approvalId = "approval-decided-recovery";
        approvals.createOrGet(new HitlApproval(approvalId, run.userId(), run.id(), "write_file", "1.0.0",
                "digest-decided", "MEDIUM", now.plusSeconds(60), HitlApproval.Decision.PENDING,
                null, null, null, null, null, 0, now, now), now);
        hitl.decide(approvalId, run.userId(), run.id(), true, "已批准");
        events.appendNext(AgentEvent.of(1, AgentEventType.APPROVAL_REQUIRED, run.sessionId(), run.id(),
                run.traceId(), Map.of("approvalId", approvalId)));

        ExecutorService worker = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        PokemonAgentRunService service = new PokemonAgentRunService(runs, events, conversations,
                messages, context -> "不会执行", worker, scheduler, hitl, null, new DurableRunProperties());
        try {
            service.recoverInFlightRuns();
            assertEquals(AgentRunStatus.FAILED, runs.findById(run.id()).orElseThrow().status());
            assertTrue(events.findAfter(run.id(), 0).stream()
                    .anyMatch(event -> event.type() == AgentEventType.RUN_FAILED
                            && "RECOVERY_UNSAFE".equals(event.payload().get("errorType"))));
        } finally {
            service.shutdown();
            hitl.shutdown();
        }
    }

    private static String contextHash(AgentRun run) {
        String value = String.join("|", safe(run.gameReleaseId()), safe(run.battleFormat()),
                safe(run.rulesetVersionId()), safe(run.language()), safe(run.dlcId()),
                safe(run.dataReleaseId()), safe(run.knowledgeReleaseId()), safe(run.indexReleaseId()),
                safe(run.contractVersion()), run.prompt(), "");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
