package com.vio.vioaiagent.agent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DamageConditionsQuestionTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    @Test void missingSourceIsExplicitAndRoleConceptCannotSilentlySkipGrounding() {
        var e = new GuideAnswerEvidence(); e.begin("想围绕一个核心组队，怎样检查功能角色是否重叠？");
        assertTrue(e.active()); assertTrue(e.needsTeamTopic()); assertTrue(e.verifiedAnswer().contains("没有取得可核对"));
        e.begin("读取我的队伍，检查功能角色是否重叠"); assertFalse(e.needsTeamTopic());
    }
    @Test void reportExplanationReadsActualGuideWithoutSelectingOrCalculatingMembers() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var search = new com.vio.vioaiagent.pokemon.guides.LocalGuideSearch(mapper, new org.springframework.core.io.DefaultResourceLoader(), "classpath:pokemon-guides/expanded-v1.json", directory.resolve("guide.sqlite").toString());
        var tool = org.mockito.Mockito.mock(org.springframework.ai.tool.ToolCallback.class);
        org.mockito.Mockito.when(tool.getToolDefinition()).thenReturn(org.springframework.ai.tool.definition.ToolDefinition.builder().name("searchPokemonGuides").description("guide").inputSchema("{}").build());
        org.mockito.Mockito.when(tool.call(org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> mapper.writeValueAsString(search.search("伤害计算 假设 双方配置 条件", "pokemon-scarlet-violet")));
        var run = com.vio.vioaiagent.run.AgentRun.create("run-report-test", "s", "c", "u", "工具报告的伤害百分比是在什么条件下成立？", "pokemon-scarlet-violet", "casual-singles", "casual-v1", "zh-CN", "trace", null, java.time.Instant.now().plusSeconds(30));
        String answer = DamageDialogueRoute.reply(new com.vio.vioaiagent.run.AgentRunContext(run), new org.springframework.ai.tool.ToolCallback[]{tool}).orElseThrow();
        assertTrue(answer.contains("calculateBattleDamage 只接"));assertTrue(answer.contains("calculateGymDamage 支持"));assertFalse(answer.contains("> 当前桥接没有表达"));
        assertTrue(answer.contains("本次未执行新计算")); assertTrue(answer.contains("作者：VIO project")); assertTrue(answer.contains("假设"));
        org.mockito.Mockito.verify(tool, org.mockito.Mockito.times(1)).call(org.mockito.ArgumentMatchers.anyString());
    }
    @Test void reportExplanationIsNotANewBattleRequest() {
        assertTrue(DamageDialogueRoute.isResultConditionsQuestion("工具报告的伤害百分比是在什么条件下成立？"));
        assertFalse(DamageDialogueRoute.isResultConditionsQuestion("计算皮卡丘打伊布的伤害百分比"));
        assertFalse(DamageDialogueRoute.isResultConditionsQuestion("工具报告的伤害百分比是在什么条件下成立？然后计算伤害"));
    }
}
