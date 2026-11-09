package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.vio.vioaiagent.run.*;
import com.vio.vioaiagent.tools.governance.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpecialistRunIntegrationTest {
    public static class EngineFixture {
        int calls;
        @Tool(description="Read deterministic damage evidence") public JsonNode calculateBattleDamage() throws Exception {calls++;return DamageAnswerEvidenceTest.result();}
    }
    public static class TeamAndEngineFixture {
        int teamCalls,calls;
        @Tool(description="Read deterministic damage evidence") public JsonNode calculateBattleDamage() throws Exception {calls++;return DamageAnswerEvidenceTest.result();}
        @Tool(description="Read complete team evidence") public com.vio.vioaiagent.pokemon.reference.TeamReferenceTools.TeamEvidence lookupPokemonTeamReference() {teamCalls++;return TeamAnswerEvidenceTest.evidence();}
    }
    @AfterEach void cleanup(){ToolExecutionContextHolder.clear();}
    @Test void actualRunExecutesPrivateDamagePlanAndPublishesOnlyValidatedNumbers() {
        var run=AgentRun.create("split","s","c","owner","请调用calculateBattleDamage计算伤害","pokemon-scarlet-violet","casual-singles","casual-v1","zh-CN","trace",null,Instant.now().plusSeconds(60));
        var events=new ArrayList<ToolGovernanceEvent>();ToolExecutionContextHolder.set(new ToolExecutionContext("owner",run.id(),Set.of("trainer"),"trace",null,events::add));
        var fixture=new EngineFixture();var model=mock(ChatModel.class);
        var call=new AssistantMessage("PRIVATE_999_DAMAGE",Map.of(),List.of(new AssistantMessage.ToolCall("calculate-1","function","calculateBattleDamage","{}")));
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(call))),new ChatResponse(List.of(new Generation(new AssistantMessage("PRIVATE_DONE")))));
        var executor=new DefaultRunExecutor(model,ToolCallbacks.from(fixture),null,null,null,null,false);executor.setSpecialistsEnabled(true);
        var chunks=new ArrayList<String>();var context=new AgentRunContext(run,chunks::add,List.of());
        String answer=executor.execute(context);
        assertEquals(1,fixture.calls);assertTrue(answer.contains("164～194 HP"));assertFalse(answer.contains("999"));assertFalse(answer.contains("PRIVATE_"));
        assertEquals(answer,String.join("",chunks));assertEquals(2,context.modelUsage().snapshot().get("modelCallCount"));
        assertTrue(events.stream().anyMatch(e->"DAMAGE_CALCULATION".equals(e.attributes().get("agentRole"))));
        verify(model,never()).stream(any(Prompt.class));
    }
    @Test void missingDamageInputsYieldClarificationWithoutClaimedCalculation() {
        var run=AgentRun.create("missing","s","c","owner","请调用calculateBattleDamage计算伤害","pokemon-scarlet-violet","casual-singles","casual-v1","zh-CN","trace",null,Instant.now().plusSeconds(60));
        ToolExecutionContextHolder.set(new ToolExecutionContext("owner",run.id(),Set.of("trainer"),"trace",null,e->{}));
        var model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("PRIVATE_GUESSED_DAMAGE_999")))));
        var fixture=new EngineFixture();var executor=new DefaultRunExecutor(model,ToolCallbacks.from(fixture),null,null,null,null,false);executor.setSpecialistsEnabled(true);
        String answer=executor.execute(new AgentRunContext(run));assertEquals(0,fixture.calls);assertTrue(answer.contains("尚未获得有效"));assertFalse(answer.contains("999"));verify(model,times(1)).call(any(Prompt.class));
    }
    @Test void mixedRunHandsTeamEvidenceToDamageRoleAndSupervisorCombinesVerifiedResults() {
        var run=AgentRun.create("mixed","s","c","owner","请分析队伍并调用calculateBattleDamage计算伤害。\n参考成员：squirtle、charmander","pokemon-scarlet-violet","casual-singles","casual-v1","zh-CN","trace",null,Instant.now().plusSeconds(60));
        var events=new ArrayList<ToolGovernanceEvent>();ToolExecutionContextHolder.set(new ToolExecutionContext("owner",run.id(),Set.of("trainer"),"trace",null,events::add));
        var fixture=new TeamAndEngineFixture();var model=mock(ChatModel.class);
        java.util.function.Function<String,ChatResponse> call=name->new ChatResponse(List.of(new Generation(new AssistantMessage("PRIVATE_ROLE_PLAN",Map.of(),List.of(new AssistantMessage.ToolCall(name,"function",name,"{}"))))));
        var done=new ChatResponse(List.of(new Generation(new AssistantMessage("PRIVATE_ROLE_FINISHED"))));
        when(model.call(any(Prompt.class))).thenReturn(call.apply("lookupPokemonTeamReference"),done,call.apply("calculateBattleDamage"),done,
                new ChatResponse(List.of(new Generation(new AssistantMessage("建议先明确每位成员的角色，再比较不同配置。")))));
        var executor=new DefaultRunExecutor(model,ToolCallbacks.from(fixture),null,null,null,null,false);executor.setSpecialistsEnabled(true);
        var chunks=new ArrayList<String>();var context=new AgentRunContext(run,chunks::add,List.of());String answer=executor.execute(context);
        assertEquals(1,fixture.teamCalls);assertEquals(1,fixture.calls);assertTrue(answer.contains("杰尼龟 | 水 | 43"));assertTrue(answer.contains("164～194 HP"));assertFalse(answer.contains("PRIVATE_"));assertEquals(answer,String.join("",chunks));
        assertEquals(Set.of("TEAM_PLANNING","DAMAGE_CALCULATION"),events.stream().map(e->e.attributes().get("agentRole")).collect(java.util.stream.Collectors.toSet()));
        assertEquals(5,context.modelUsage().snapshot().get("modelCallCount"));verify(model,never()).stream(any(Prompt.class));
    }
}
