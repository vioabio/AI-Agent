package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.security.*;
import com.vio.vioaiagent.tools.CompetitiveTrendsTool;
import com.vio.vioaiagent.tools.governance.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompetitiveTrendsAgentTest {
    private void exercise(String question, String role, boolean networkExpected, String expected) {
        var hitl = new HitlManager();
        var masker = new ParameterMasker();
        var gateway = new ToolExecutionGateway(hitl, new PathGuard(List.of(System.getProperty("user.dir") + "/tmp")), new CommandGuard(List.of()), masker, new AuditLogger(masker));
        var calls = new AtomicInteger();
        var tool = new CompetitiveTrendsTool(url -> {
            calls.incrementAndGet();
            return url.endsWith("/stats/") ? "<a href='2026-10/'>month</a>" : "Total battles: 12345\n| 1 | Great Tusk | 28.31876% | 1 |\n";
        }, Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC));
        var events = new ArrayList<ToolGovernanceEvent>();
        ToolExecutionContextHolder.set(new ToolExecutionContext("test-user", "test-" + UUID.randomUUID(), Set.of(role), "test-trace", null, events::add));
        try {
            var teamTool = new com.vio.vioaiagent.tools.CompetitiveTeamsTool(url -> {
                calls.incrementAndGet();
                if(question.contains("来源失败")) return "<html>Checking your browser</html>";
                return url.equals(com.vio.vioaiagent.tools.CompetitiveTeamsTool.SOURCE)
                    ? com.vio.vioaiagent.tools.CompetitiveTeamsToolTest.index() : com.vio.vioaiagent.tools.CompetitiveTeamsToolTest.paste();
            }, Clock.systemUTC());
            var tools = Arrays.stream(ToolCallbacks.from(tool,teamTool)).map(gateway::wrap).toArray(org.springframework.ai.tool.ToolCallback[]::new);
            var model = mock(ChatModel.class);
            var agent = new ToolCallAgent(tools);
            agent.setChatClient(org.springframework.ai.chat.client.ChatClient.builder(model).build());
            clearInvocations(model); // Client construction reads default options without generating text.
            agent.setToolExecutionGateway(gateway); agent.setCurrentUserRequest(question);
            List<String> chunks = new ArrayList<>(); agent.setAnswerDeltaSink(chunks::add);
            agent.run(question);
            assertTrue(agent.isProgramOnlyTrends()); verifyNoInteractions(model);
            assertTrue(agent.getFinalAnswer().contains(expected), agent.getFinalAnswer());
            assertEquals(networkExpected ? 2 : 0, calls.get());
            if(question.contains("详细配置")) assertFalse(agent.getFinalAnswer().contains("| 排名 | 宝可梦 |"));
            assertEquals(agent.getFinalAnswer(), String.join("", chunks));
            if (networkExpected && !question.contains("详细配置")) {
                assertTrue(agent.getFinalAnswer().contains("2026-10"));
                assertTrue(agent.getFinalAnswer().contains("不是胜率"));
                assertFalse(events.isEmpty());
                assertTrue(events.stream().allMatch(e -> "PROGRAM_CURRENT_META_SOURCE".equals(e.attributes().get("selectionOrigin"))));
            }
        } finally { ToolExecutionContextHolder.clear(); gateway.shutdown(); hitl.shutdown(); }
    }
    @Test void completeTeamsPublishAllSixSetsInsteadOfAUsageTable() {
        exercise("当前朱紫 OU 单打有哪些热门队伍？显示队伍及其详细配置", "trainer", true, "Pokemon5 @ Leftovers");
    }
    @Test void completeTeamsCannotBypassRoles() {
        exercise("当前朱紫OU热门队伍及详细配置", "unprivileged", false, "完整队伍来源暂未取得");
    }
    @Test void unavailableTeamSourcesNeverFallBackToIndividualRankings() {
        exercise("当前朱紫OU热门队伍详细配置来源失败", "trainer", true, "完整队伍来源暂未取得");
    }
    @Test void explicitOuFetchesThroughGovernanceAndPublishesActualNumbersWithoutModelInvention() {
        exercise("当前朱紫OU单打使用率排名", "trainer", true, "28.31876%");
    }
    @Test void ambiguousCasualRequestClarifiesWithoutNetworkOrModelCalls() {
        exercise("当前有哪些热门队伍", "trainer", false, "先选一个统计范围");
    }
    @Test void rolesCannotBeBypassedByProgramSelection() {
        exercise("当前朱紫OU单打使用率排名", "unprivileged", false, "在线资料暂未取得");
    }
    @Test void vgcWithoutSearchKeyDoesNotFabricateACompletedSearch() {
        exercise("当前朱紫VGC Regulation I热门队伍", "trainer", false, "本次没有执行赛事检索");
    }
}
