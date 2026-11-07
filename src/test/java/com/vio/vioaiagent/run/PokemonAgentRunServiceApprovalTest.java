package com.vio.vioaiagent.run;

import com.vio.vioaiagent.tools.governance.ToolExecutionContextHolder;
import com.vio.vioaiagent.tools.governance.ToolGovernanceEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M3 审批拒绝回归：结论事件必须先于唯一 FAILED 终态且可重放。 */
class PokemonAgentRunServiceApprovalTest {

    @Test
    void rejectionPublishesApprovalResolutionBeforeFailure() throws Exception {
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventRepository events = new InMemoryEventRepository();
        InMemoryConversationRepository conversations = new InMemoryConversationRepository();
        InMemoryChatMessageRepository messages = new InMemoryChatMessageRepository();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        RunExecutor executor = context -> {
            ToolExecutionContextHolder.current().eventSink().accept(new ToolGovernanceEvent(
                    "approval.required", context.run().id(), "export-report", "approval-1",
                    Instant.now(), Map.of("riskLevel", "MEDIUM")));
            return "不会发布的结果";
        };
        PokemonAgentRunService service = new PokemonAgentRunService(runs, events, conversations,
                messages, executor, worker, scheduler);
        try {
            RunView created = service.createRun(new CreateRunCommand("user-1", "session-1", null,
                    "需要审批", "sv-1", "singles", "rules-1", "zh-CN", "trace-1", null,
                    Duration.ofSeconds(10)));

            Instant deadline = Instant.now().plusSeconds(2);
            AgentRunStatus status;
            do {
                status = service.findRun(created.runId(), "user-1").orElseThrow().status();
                if (status == AgentRunStatus.WAITING_APPROVAL) break;
                Thread.sleep(10);
            } while (Instant.now().isBefore(deadline));
            assertEquals(AgentRunStatus.WAITING_APPROVAL, status);

            service.resolveApproval(created.runId(), "user-1", false, "拒绝导出");
            List<AgentEvent> stream = service.events(created.runId(), "user-1", 0).events();
            int resolved = indexOf(stream, AgentEventType.APPROVAL_RESOLVED);
            int failed = indexOf(stream, AgentEventType.RUN_FAILED);
            assertTrue(resolved >= 0, "必须记录 approval.resolved");
            assertTrue(failed > resolved, "审批结论必须先于 run.failed");
            assertEquals(AgentRunStatus.FAILED,
                    service.findRun(created.runId(), "user-1").orElseThrow().status());
            assertEquals(1, stream.stream().filter(event -> event.type().isTerminal()).count());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void approvalReasonIsBoundedAndRedactedBeforeRunEvents() throws Exception {
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventRepository events = new InMemoryEventRepository();
        InMemoryConversationRepository conversations = new InMemoryConversationRepository();
        InMemoryChatMessageRepository messages = new InMemoryChatMessageRepository();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        RunExecutor executor = context -> {
            ToolExecutionContextHolder.current().eventSink().accept(new ToolGovernanceEvent(
                    "approval.required", context.run().id(), "export-report", "approval-1",
                    Instant.now(), Map.of("riskLevel", "MEDIUM")));
            return "不会发布的结果";
        };
        PokemonAgentRunService service = new PokemonAgentRunService(runs, events, conversations,
                messages, executor, worker, scheduler);
        try {
            RunView created = service.createRun(new CreateRunCommand("user-1", "session-1", null,
                    "需要审批", "sv-1", "singles", "rules-1", "zh-CN", "trace-1", null,
                    Duration.ofSeconds(10)));

            Instant deadline = Instant.now().plusSeconds(2);
            AgentRunStatus status;
            do {
                status = service.findRun(created.runId(), "user-1").orElseThrow().status();
                if (status == AgentRunStatus.WAITING_APPROVAL) break;
                Thread.sleep(10);
            } while (Instant.now().isBefore(deadline));
            assertEquals(AgentRunStatus.WAITING_APPROVAL, status);

            String reason = "拒绝导出\r\n token=super-secret " + "x".repeat(2_100);
            service.resolveApproval(created.runId(), "user-1", false, reason);
            List<AgentEvent> stream = service.events(created.runId(), "user-1", 0).events();
            AgentEvent resolved = stream.stream()
                    .filter(event -> event.type() == AgentEventType.APPROVAL_RESOLVED)
                    .findFirst().orElseThrow();
            AgentEvent failed = stream.stream()
                    .filter(event -> event.type() == AgentEventType.RUN_FAILED)
                    .findFirst().orElseThrow();

            String resolvedReason = String.valueOf(resolved.payload().get("reason"));
            String failedReason = String.valueOf(failed.payload().get("reason"));
            assertTrue(resolvedReason.length() <= 2_000);
            assertTrue(failedReason.length() <= 2_000);
            assertTrue(!resolvedReason.contains("\r") && !resolvedReason.contains("\n"));
            assertTrue(!failedReason.contains("super-secret"));
            assertTrue(resolvedReason.contains("[REDACTED]"));
        } finally {
            service.shutdown();
        }
    }

    private int indexOf(List<AgentEvent> events, AgentEventType type) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).type() == type) return i;
        }
        return -1;
    }
}
