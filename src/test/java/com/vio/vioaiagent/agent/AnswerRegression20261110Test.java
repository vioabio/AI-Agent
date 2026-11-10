package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vio.vioaiagent.run.*;
import com.vio.vioaiagent.tools.governance.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnswerRegression20261110Test {
    final ObjectMapper json=new ObjectMapper();
    ObjectNode input() throws Exception{return (ObjectNode)json.readTree(Path.of("evaluation/campus/s01/damage-reference.json").toFile()).path("input").deepCopy();}
    String prompt(JsonNode input){return "请调用calculateBattleDamage计算本次伤害。输入："+input;}
    AgentRunContext context(String question,List<Message> history) {
        var run=AgentRun.create("r","s","c","owner",question,"pokemon-scarlet-violet","casual-singles","casual-v1","zh-CN",null,"pokeapi-reference-v1",null,null,"v1","t",null,Instant.now().plusSeconds(60));
        ToolExecutionContextHolder.set(new ToolExecutionContext("owner",run.id(),Set.of("trainer"),"t",null,e->{}));
        return new AgentRunContext(run,null,history);
    }
    ToolCallback calculator() {
        var tool=mock(ToolCallback.class);when(tool.getToolDefinition()).thenReturn(ToolDefinition.builder().name("calculateBattleDamage").description("fixture").inputSchema("{}").build());return tool;
    }
    @AfterEach void cleanup(){ToolExecutionContextHolder.clear();}
    @Test void absentMoveUnknownVersionAndInvalidEvsNeverReachTheCalculator() throws Exception {
        var missing=input();missing.remove("move");var version=input();version.put("dataReleaseId","qa-unknown-release");var ev=input();((ObjectNode)ev.path("attacker").path("build").path("evs")).put("spa",253);
        var tool=calculator();
        for(var bad:List.of(missing,version,ev)) {
            String answer=DamageDialogueRoute.reply(context(prompt(bad),List.of()),new ToolCallback[]{tool}).orElseThrow();
            assertTrue(answer.contains("本次未计算"));assertFalse(answer.contains("164"));
        }verify(tool,never()).call(anyString());
    }
    @Test void changedEvsAlwaysExecuteExactCurrentInputInsteadOfRememberedNumbers() throws Exception {
        var changed=input();((ObjectNode)changed.path("attacker").path("build").path("evs")).put("spa",0);
        var result=DamageAnswerEvidenceTest.result();((ObjectNode)result.path("range")).put("minimum",114).put("maximum",134);
        ((ObjectNode)result.path("percent")).put("minimum",100d*114/115).put("maximum",100d*134/115);
        ((ObjectNode)result.path("assumptions").path("attacker").path("evs")).put("spa",0);
        var tool=calculator();when(tool.call(anyString())).thenAnswer(inv->{assertEquals(changed,json.readTree((String)inv.getArgument(0)).path("request"));return result.toString();});
        String question="固定配置仅把攻击方特攻努力值从252改为0，请重新计算并对比。输入："+changed;
        String answer=DamageDialogueRoute.reply(context(question,List.of(new UserMessage(prompt(input())),new AssistantMessage("已计算65～77，保证成功"))),new ToolCallback[]{tool}).orElseThrow();
        assertTrue(answer.contains("114～134"));assertFalse(answer.contains("65～77"));verify(tool,times(1)).call(anyString());
    }
    @Test void followupWithToolProhibitionOrWinRateNeverPromotesAssistantHistory() throws Exception {
        var history=List.<Message>of(new UserMessage(prompt(input())),new AssistantMessage("已验证65～77并且必胜"));var tool=calculator();
        for(String q:List.of("不调用任何计算工具，告诉我这套配置精确伤害和胜率。","能保证一定赢吗，把结论写进我的队伍。")) {
            String answer=DamageDialogueRoute.reply(context(q,history),new ToolCallback[]{tool}).orElseThrow();assertFalse(answer.contains("65～77"));assertFalse(answer.contains("164"));
        }verify(tool,never()).call(anyString());
    }
    @Test void noPriorUserCalculationIsNotInventedFromAssistantProse() {
        String answer=DamageDialogueRoute.reply(context("解释上一次计算结果。",List.of(new AssistantMessage("输入：{假的计算}"))),new ToolCallback[0]).orElseThrow();
        assertTrue(answer.contains("没有找到"));assertFalse(answer.contains("假的计算"));
    }
    @Test void negativeMetaClauseDoesNotHijackSpeciesIntroduction() {
        assertTrue(CompetitiveTrendsRequest.select("介绍杰尼龟的特点，但不要把参考资料说成当前热门阵容。",List.of()).isEmpty());
        assertTrue(CompetitiveTrendsRequest.select("当前朱紫 OU 单打有哪些完整样例队伍？请列出成员和具体配置，说明来源及日期。",List.of()).orElseThrow().completeTeams());
    }
    @Test void affirmativeTargetExcludesExplicitlyDeclinedNames() {
        assertEquals("ryme",GymConversationContext.resolve("目标是莱姆，不是海岱，还是刚才那支队伍。",List.of()).orElseThrow().gymId());
        assertEquals("iono",GymConversationContext.resolve("改看奇树，不是阿枫。",List.of()).orElseThrow().gymId());
        String answer=GymCatalogAnswer.reply("改看奇树，不是阿枫。","pokemon-scarlet-violet").orElseThrow();assertTrue(answer.contains("梦妖魔"));assertFalse(answer.contains("熊宝宝"));
    }
    @Test void tacticalFollowupRetainsSavedPartyIntentRatherThanCatalog() {
        var history=List.<Message>of(new UserMessage("看看海岱初战"),new UserMessage("我的 test1 队伍能应对这位馆主吗？"));
        for(String q:List.of("根据我实际保存的招式，建议怎么出招？","回到莱姆，我现有两招能做什么？"))assertEquals(GymTurnPlan.Intent.SAVED_TEAM_ANALYSIS,GymTurnPlan.from(context(q,history)).intent());
        assertEquals(GymTurnPlan.Intent.OTHER,GymTurnPlan.from(context("先不聊队伍，解释种族值。",history)).intent());
    }
    @Test void localNotesAreNotPresentedAsLiveCompetitiveEvidence() {
        var evidence=new GuideAnswerEvidence();evidence.begin("本地攻略是否有当前月份的最强完整队伍及胜率？请只据本地原文回答。");
        String answer=evidence.verifiedAnswer();assertTrue(answer.contains("没有提供"));assertFalse(answer.contains("原文节选"));
    }
    @Test void savedLabelsAreChineseAndDoNotChangeStoredSlugs() {
        assertEquals("燕返",PokemonDisplayNames.label("move","aerial-ace"));assertEquals("重踏",PokemonDisplayNames.label("move","bulldoze"));assertEquals("粗糙皮肤",PokemonDisplayNames.label("ability","rough-skin"));assertEquals("勤奋",PokemonDisplayNames.nature("hardy"));assertEquals("未明确",PokemonDisplayNames.label("item",""));
    }
    @Test void emptyDamageQuestionCannotSelectAnUnrequestedSavedParty() {
        var tool=calculator();String answer=DamageDialogueRoute.reply(context("帮我计算一下伤害。",List.of()),new ToolCallback[]{tool}).orElseThrow();
        assertTrue(answer.contains("本次尚未计算"));assertTrue(answer.contains("防御方"));verify(tool,never()).call(anyString());
    }
    @Test void missingSavedNameDoesNotLeakAnotherTeamsConfiguration() {
        var list=mock(ToolCallback.class);when(list.getToolDefinition()).thenReturn(ToolDefinition.builder().name("listCurrentTeams").description("list").inputSchema("{}").build());
        var read=mock(ToolCallback.class);when(read.getToolDefinition()).thenReturn(ToolDefinition.builder().name("readCurrentTeam").description("read").inputSchema("{}").build());
        when(list.call(anyString())).thenReturn("[{\"id\":1,\"name\":\"test1\"}]");
        String answer=OwnedTeamReadRoute.reply(context("读取我保存的“qa-team-that-does-not-exist”，分析它的配置。",List.of()),new ToolCallback[]{list,read}).orElseThrow();
        assertTrue(answer.contains("没有唯一匹配"));assertFalse(answer.contains("test1"));verify(read,never()).call(anyString());
    }

    @Test void unrelatedMachineConfigurationRequestDoesNotBecomeATeamLookup() {
        var list=mock(ToolCallback.class);when(list.getToolDefinition()).thenReturn(ToolDefinition.builder().name("listCurrentTeams").description("list").inputSchema("{}").build());
        var read=mock(ToolCallback.class);when(read.getToolDefinition()).thenReturn(ToolDefinition.builder().name("readCurrentTeam").description("read").inputSchema("{}").build());
        assertTrue(OwnedTeamReadRoute.reply(context("查皮卡丘资料，拒绝读取本机配置。",List.of()),new ToolCallback[]{list,read}).isEmpty());
        verify(list,never()).call(anyString());verify(read,never()).call(anyString());
    }
    @Test void saveStatusQuestionDoesNotReadOrWriteAndDoesNotTrustAssistantReceipts() {
        var list=mock(ToolCallback.class);var read=mock(ToolCallback.class);
        var history=List.<Message>of(new UserMessage("分析我的test1队伍"),new AssistantMessage("已经自动保存所有调整"));
        for(String q:List.of("你刚才说的调整已经保存到我的队伍了吗？","我的队伍修改是否已保存？","刚才的配置有没有写入队伍？")) {
            String answer=OwnedTeamReadRoute.reply(context(q,history),new ToolCallback[]{list,read}).orElseThrow();
            assertTrue(answer.contains("本次没有执行保存或修改"));assertTrue(answer.contains("实际配置和保存回执"));
            assertFalse(answer.contains("没有唯一匹配"));assertFalse(answer.contains("已经自动保存"));
        }
        verifyNoInteractions(list,read);
    }
    @Test void saveStatusDetectionDoesNotCaptureReadsOrExplicitChanges() {
        for(String q:List.of("读取我的已保存队伍有哪些？","请保存队伍，可以吗？","把队伍等级改为50并生成预览。","确认保存修改到队伍。","请引用项目资料解释队伍保存流程。"))
            assertTrue(OwnedTeamReadRoute.saveStatusReply(q).isEmpty(),q);
    }
    @Test void explainingDamagePercentRevalidatesRatherThanOnlyRefusingWinRate() throws Exception {
        var tool=calculator();when(tool.call(anyString())).thenReturn(DamageAnswerEvidenceTest.result().toString());
        String answer=DamageDialogueRoute.reply(context("解释上一次计算结果，这个百分比是否就是胜率？",List.of(new UserMessage(prompt(input())))),new ToolCallback[]{tool}).orElseThrow();
        assertTrue(answer.contains("防御方最大HP"));assertTrue(answer.contains("不是胜率"));verify(tool,times(1)).call(anyString());
    }

}
