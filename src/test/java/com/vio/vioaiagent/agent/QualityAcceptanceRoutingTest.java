package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.run.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QualityAcceptanceRoutingTest {
    private AgentRunContext context(String question, List<Message> history) {
        return new AgentRunContext(AgentRun.create(UUID.randomUUID().toString(), "s", "c", "owner", question,
                "pokemon-scarlet-violet", "casual-singles", "casual-v1", "zh-CN", "trace", null,
                Instant.now().plusSeconds(60)), null, history);
    }

    @Test void mathematicalPresentationRequestsKeepOnlyVerifiedFactors() {
        assertTrue(TypeMatchupReply.reply("火属性招式攻击草／钢双属性目标，各项倍率和乘积分别是多少？只看基础属性表。").orElseThrow().contains("2.0× × 2.0× | 4.0×"));
        assertTrue(TypeMatchupReply.reply("龙属性招式攻击钢／妖精双属性，每一步怎么乘？").orElseThrow().contains("0.5× × 0.0× | 0.0×"));
        assertTrue(TypeMatchupReply.reply("地面属性招式攻击虫／飞行双属性时，是抵抗还是免疫？写出乘法过程。").orElseThrow().contains("0.5× × 0.0× | 0.0×"));
        assertTrue(TypeMatchupReply.reply("电和草分别打水／超能力，请把对水、对超能力、最终乘积都逐项写清楚。").orElseThrow().contains("2.0× × 1.0× | 2.0×"));
        assertTrue(TypeMatchupReply.reply("地面打虫／飞行，写出乘法过程，然后帮我保存队伍。").isEmpty());
    }

    @Test void oneSentenceSpecialtyUsesTheCatalogWithoutExpandingTheTeam() {
        String question = "只用一句话回答，奇树的道馆擅长哪一种属性？不要列队伍。";
        assertTrue(GymFocusedFactReply.requestsSpecialty(question));
        String answer = GymDialogueRouter.reply(context(question, List.of()), new ToolCallback[0]).orElseThrow();
        assertTrue(answer.contains("奇树擅长**电属性**"));
        assertFalse(answer.contains("| 成员 |"));
        assertFalse(answer.contains("\n"));
    }

    @Test void stageComparisonKeepsTheCurrentRematchAndReportsBothLevels() {
        String answer = GymDialogueRouter.reply(context("王牌多少级，和初战是同一个等级吗？",
                List.of(new UserMessage("列出莱姆视察重赛的成员与等级。"))), new ToolCallback[0]).orElseThrow();
        assertTrue(answer.contains("42级"));
        assertTrue(answer.contains("66级"));
        assertTrue(answer.contains("颤弦蝾螈"));
        String mode = GymDialogueRouter.reply(context("现在切回初战，这场是哪种对战模式？",
                List.of(new UserMessage("列出莱姆视察重赛的成员与等级。"),
                        new UserMessage("王牌多少级，和初战是同一个等级吗？"))), new ToolCallback[0]).orElseThrow();
        assertTrue(mode.contains("莱姆的初战是双打"));
        assertFalse(mode.contains("单打"));
        assertTrue(GymFocusedFactReply.requestsBattleMode("这场采用单打还是双打？只给结论。"));
    }

    @Test void shortOrdinalsAreBoundToAnActuallyDisplayedCatalog() {
        String prompt = "请按等级推荐顺序列出八位馆主。";
        String list = GymCatalogAnswer.reply(prompt, "pokemon-scarlet-violet").orElseThrow();
        var history = List.<Message>of(new UserMessage(prompt), new AssistantMessage(list));
        String answer = GymDialogueRouter.reply(context("我想看第4位的初战队伍。", history), new ToolCallback[0]).orElseThrow();
        assertTrue(answer.contains("海岱"));
        assertTrue(answer.contains("蟹钳锤"));
        assertTrue(answer.contains("铁拳"));
        assertFalse(answer.contains("无效"));
        assertTrue(GymOrdinalReference.resolve("看第4位的队伍", List.of()).isEmpty());
    }

    @Test void historyFillersCannotDisplaceThePersistedUserTarget() {
        var history = new ArrayList<Message>();
        String first = "先记住我的目标是海岱初战。";
        assertTrue(ConversationRecallReply.reply(context(first, history)).orElseThrow().contains("海岱初战"));
        history.add(new UserMessage(first));
        for (int i = 1; i <= 9; i++) {
            String filler = "这轮只回复“收到”，这是历史填充第" + i + "轮。";
            assertEquals("收到", ConversationRecallReply.reply(context(filler, history)).orElseThrow());
            history.add(new UserMessage(filler));
            history.add(new AssistantMessage("收到"));
        }
        String answer = GymDialogueRouter.reply(context("回到前面的目标馆主，告诉我他的名字和擅长属性；如果历史不足请明确说。", history), new ToolCallback[0]).orElseThrow();
        assertTrue(answer.contains("海岱擅长**水属性**"));
    }

    @Test void unnamedSavedFollowupsCanReuseAnExplicitOwnerScopedSelection() throws Exception {
        var teams = new ObjectMapper().readTree("[{\"id\":7,\"name\":\"test1\"}]");
        assertEquals(7, OwnedTeamReadRoute.select(teams, "用这支已保存队伍挑战阿枫初战", List.of(new UserMessage("请读取我保存的test1，只列实际成员。")), true).orElseThrow().path("id").asInt());
        assertTrue(GymConversationContext.savedTeamRequest("test1目前的招式适合打海岱初战吗？请读取配置。"));
    }

    @Test void publicCompleteTeamRequestsDoNotReadPrivateLists() {
        String question = "给我朱紫gen9ou的完整样例队伍与每只配置，要能核对来源；读取不了时给可点击的原网页链接。";
        assertTrue(CompetitiveTrendsRequest.select(question, List.of()).orElseThrow().completeTeams());
        var tool = mock(ToolCallback.class);
        assertTrue(OwnedTeamReadRoute.reply(context(question, List.of()), new ToolCallback[]{tool}).isEmpty());
        verifyNoInteractions(tool);
    }

    @Test void workflowExplanationsRecognizeActionsRejectionAndUnsavedSuggestions() {
        for (String question : List.of("请引用项目攻略解释：预览里的培养修改，经过哪些动作才会真正保存？用户拒绝后会怎样？",
                "我只是点开AI给出的修改建议，没有点确认，这是不是已经落盘了？请用项目说明作为依据。"))
            assertEquals(ConfigurationExplanationRoute.Topic.SAVE_WORKFLOW, ConfigurationExplanationRoute.select(question).orElseThrow());
    }

    @Test void quotedMissingGuideSubjectsCannotBeSatisfiedByUnrelatedChunks() {
        var tool = mock(ToolCallback.class);
        when(tool.getToolDefinition()).thenReturn(ToolDefinition.builder().name("searchPokemonGuides").description("read").inputSchema("{}").build());
        when(tool.call(anyString())).thenReturn("[]");
        String answer = LocalGuideOnlyRoute.reply(context("项目攻略里有“验收虚构流派QA17无限回血阵”的详细操作吗？找不到就明确说明缺失，不要代写攻略。", List.of()), new ToolCallback[]{tool}).orElseThrow();
        assertTrue(answer.contains("未找到"));
        assertFalse(answer.contains("攻略参考（原文节选）"));
    }

    @Test void invalidEvIsExplainedBeforeInvokingTheEngine() throws Exception {
        var input = new ObjectMapper().readTree(Files.readString(Path.of("evaluation/campus/s01/damage-reference.json"))).path("input");
        ((com.fasterxml.jackson.databind.node.ObjectNode)input.path("attacker").path("build").path("evs")).put("spa", 253);
        String question = "请计算以下输入，特攻EV故意填253，遇到非法参数请指出，不要自动改成252。输入：" + input;
        assertTrue(ExplicitDamageRequest.hasInput(question));
        assertTrue(ExplicitDamageRequest.arguments(question).isEmpty());
        assertTrue(ExplicitDamageRequest.invalidInputReply(question).contains("特攻努力值EV超出0～252"));
    }
}
