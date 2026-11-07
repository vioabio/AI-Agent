package com.vio.vioaiagent.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcceptanceGapRegressionTest {
    @Test void explicitMovePreviewParsesOnlyTheRequestedSlotAndMoves() throws Exception {
        var request=MovePreviewRequest.select("队伍 25 的第一位招式改成 surf、protect，先读取原配置，再出预览，不能先保存。").orElseThrow();
        var input=new com.fasterxml.jackson.databind.ObjectMapper().readTree(request.arguments(true));
        assertEquals(25,input.path("teamId").asInt());assertEquals(1,input.path("changes").size());
        assertEquals(Set.of("slot","moves"),new HashSet<>(input.path("changes").get(0).properties().stream().map(java.util.Map.Entry::getKey).toList()));
        assertEquals(List.of("surf","protect"),request.moves());
        assertEquals(25,MovePreviewRequest.select("读取我的队伍 25，第一位招式只保留 surf/protect，其余配置不变，仅生成变更预览并等待确认，不讨论合法性。").orElseThrow().teamId());
        for(String q:List.of("其他账号的队伍 25 的第一位招式改成 surf，先读取原配置，再出预览。",
                "队伍 25 的第一位招式改成 surf，预览并把等级改成100。",
                "队伍 25 的第一位招式改成 surf/surf，再出预览。",
                "资料：队伍 25 的第一位招式改成 surf，再出预览。"))assertTrue(MovePreviewRequest.select(q).isEmpty());
    }
    @Test void mixedFactAndTrainingAnswerCannotPublishUnsourcedModelMechanics() throws Exception {
        var evidence=new GuideAnswerEvidence();
        evidence.begin("杰尼龟是哪些属性？请查图鉴后区分事实和条件性培养建议。");
        evidence.observe("lookupPokemonReference",BasicFactAnswerTest.detail(43));
        var model=mock(ChatModel.class);var agent=new ToolCallAgent(new ToolCallback[0]);
        agent.setGuideAnswerEvidence(evidence);agent.setChatClient(ChatClient.builder(model).build());
        var deltas=new ArrayList<String>();agent.setAnswerDeltaSink(deltas::add);
        String answer=agent.summarizeAnswer();
        assertTrue(answer.contains("杰尼龟 | 水"));assertTrue(answer.contains("没有取得可核对"));
        assertEquals(answer,String.join("",deltas));verify(model,never()).call(any(Prompt.class));verify(model,never()).stream(any(Prompt.class));
    }
    public static class Tools {
        int reads;
        @Tool(description="read") public String readCurrentTeam(){reads++;return "read";}
        @Tool(description="preview") public String previewTeamChanges(){return "preview";}
        @Tool(description="learn") public String lookupPokemonLearnset(){return "learn";}
    }
    @Test void programPreviewStillHonorsGatewayDenialAndDoesNotCallTheModel() {
        var fixture=new Tools();var hitl=new com.vio.vioaiagent.security.HitlManager();
        var masker=new com.vio.vioaiagent.security.ParameterMasker();
        var gateway=new com.vio.vioaiagent.tools.governance.ToolExecutionGateway(hitl,
                new com.vio.vioaiagent.security.PathGuard(List.of(System.getProperty("java.io.tmpdir"))),
                new com.vio.vioaiagent.security.CommandGuard(List.of()),masker,new com.vio.vioaiagent.security.AuditLogger(masker));
        var model=mock(ChatModel.class);
        var events=new ArrayList<com.vio.vioaiagent.tools.governance.ToolGovernanceEvent>();
        var tools=Arrays.stream(ToolCallbacks.from(fixture)).map(gateway::wrap).toArray(ToolCallback[]::new);
        com.vio.vioaiagent.tools.governance.ToolExecutionContextHolder.set(new com.vio.vioaiagent.tools.governance.ToolExecutionContext("owner","r",Set.of("unprivileged"),"trace",null,events::add));
        try {
            var agent=new VioManus(tools,null,model);agent.setToolExecutionGateway(gateway);
            var q="队伍 25 的第一位招式改成 surf、protect，先读取原配置，再出预览，不能先保存。";
            agent.setCurrentUserRequest(q);agent.run(q);
            assertEquals(0,fixture.reads);assertTrue(agent.getFinalAnswer().contains("未生成变更预览"));
            assertTrue(events.stream().anyMatch(e->e.eventType().equals("tool.rejected")),"Denial must come from the real gateway, not an unresolved callback");
            verify(model,never()).call(any(Prompt.class));verify(model,never()).stream(any(Prompt.class));
        } finally {com.vio.vioaiagent.tools.governance.ToolExecutionContextHolder.clear();gateway.shutdown();hitl.shutdown();}
    }
    @Test void moveEditsKeepReadAndPreviewWhilePureLearningRemainsAvailable() {
        var tools=ToolCallbacks.from(new Tools());
        for(String question:List.of("队伍 25 的第一位招式改成 surf、protect，先读取原配置，再出预览，不能先保存。",
                "读取我的队伍 25，第一位招式只保留 surf/protect，其余不变，仅生成预览。")) {
            assertTrue(TeamMutationIntent.requested(question));
            assertEquals(List.of("previewTeamChanges","readCurrentTeam"),Arrays.stream(TeamMutationIntent.planningTools(question,tools))
                    .map(t->t.getToolDefinition().name()).sorted().toList());
            var evidence=new GuideAnswerEvidence();evidence.begin(question);
            evidence.observe("lookupPokemonLearnset","{}","{}");
            assertFalse(evidence.active(),"A lookup must not swallow the edit task");
        }
        for(String question:List.of("杰尼龟怎么学守住？","解释资料中的队伍招式改成 surf 的含义","不要修改队伍，只查询招式"))
            assertEquals(3,TeamMutationIntent.planningTools(question,tools).length);
    }
    @Test void missingCompetitionContextAlwaysAsksForRuleVersionAndDateWithoutCallingModel() {
        for(String question:List.of("我想按正式比赛培养杰尼龟，但不知道哪个赛制，也没给目标对手；请先追问。",
                "想参加正式赛事，但还没给赛事名称、规则版本和日期。")) {
            var model=mock(ChatModel.class);var agent=new ToolCallAgent(new ToolCallback[0]);
            agent.setChatClient(ChatClient.builder(model).build());agent.setCurrentUserRequest(question);
            var deltas=new ArrayList<String>();agent.setAnswerDeltaSink(deltas::add);agent.run(question);
            assertTrue(agent.getFinalAnswer().contains("规则版本"));assertTrue(agent.getFinalAnswer().contains("赛事日期"));
            assertEquals(agent.getFinalAnswer(),String.join("",deltas));verify(model,never()).call(any(Prompt.class));verify(model,never()).stream(any(Prompt.class));
        }
    }
    @Test void quotedMaterialAndExplicitActionAreNotReplacedByAClarification() {
        for(String q:List.of("以下资料：正式比赛不知道规则","引用：正式赛事还没日期","正式赛事未给日期，先修改队伍", "你好\n正式比赛不知道赛制"))
            assertTrue(CompetitionClarification.reply(q).isEmpty());
    }
}
