package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.run.*;
import com.vio.vioaiagent.observability.*;
import com.vio.vioaiagent.tools.governance.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpecialistAgentCoordinatorTest {
    final ChatModel model=mock(ChatModel.class);
    final List<ToolGovernanceEvent> events=new ArrayList<>();
    ToolExecutionContext parent;
    AgentRunContext context(String question) {
        var run=AgentRun.create("specialists","s","c","owner",question,"pokemon-scarlet-violet","casual-singles","casual-v1","zh-CN","trace",null,Instant.now().plusSeconds(60));
        parent=new ToolExecutionContext("owner",run.id(),Set.of("trainer"),"trace",null,e->{assertNotNull(e.eventId());events.add(e);});ToolExecutionContextHolder.set(parent);
        return new AgentRunContext(run);
    }
    ToolCallback tool(String name) {
        var callback=mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder().name(name).description("Fixture "+name).inputSchema("{\"type\":\"object\",\"properties\":{}}").build());
        return callback;
    }
    ChatResponse call(String name,String args) {return new ChatResponse(List.of(new Generation(new AssistantMessage("PRIVATE_PLAN_NOT_EVIDENCE",Map.of(),List.of(new AssistantMessage.ToolCall(UUID.randomUUID().toString(),"function",name,args))))));}
    ChatResponse stop() {return new ChatResponse(List.of(new Generation(new AssistantMessage("PRIVATE_ALREADY_SAVED_OR_999_DAMAGE"))));}
    void observe(SpecialistAgentCoordinator coordinator,SpecialistAgentCoordinator.Decision decision,String output) {
        var call=decision.response().getResult().getOutput().getToolCalls().getFirst();
        coordinator.observe(new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse(call.id(),call.name(),output))));
    }
    @AfterEach void cleanup() {ToolExecutionContextHolder.clear();Thread.interrupted();}

    @Test void rolesExposeOnlyTheirOwnIntersectionAndNoWritesOrMcp() {
        for(var entry:Map.of("查皮卡丘图鉴",SpecialistRole.POKEMON_RESEARCH,"分析我的队伍",SpecialistRole.TEAM_PLANNING,"请计算伤害",SpecialistRole.DAMAGE_CALCULATION).entrySet()) {
            var c=new SpecialistAgentCoordinator(context(entry.getKey()),model);
            when(model.call(any(Prompt.class))).thenAnswer(inv->{
                var p=(Prompt)inv.getArgument(0);var options=(ToolCallingChatOptions)p.getOptions();
                assertFalse(options.getInternalToolExecutionEnabled());
                var exposed=options.getToolCallbacks().stream().map(t->t.getToolDefinition().name()).toList();
                assertTrue(entry.getValue().tools().containsAll(exposed));assertFalse(exposed.contains("previewTeamChanges"));assertFalse(exposed.contains("terminal"));
                assertTrue(p.getContents().contains(entry.getValue().name()));return stop();
            });
            c.plan(new ToolCallback[]{tool("lookupPokemonReference"),tool("calculateBattleDamage"),tool("previewTeamChanges"),tool("terminal")},"").orElseThrow();
        }
    }
    @Test void teamThenDamageSharesOnlyActualToolPairsAndPreservesParentContext() {
        var c=new SpecialistAgentCoordinator(context("分析队伍并计算伤害"),new ObservedChatModel(model,new ModelUsageLedger()));
        when(model.call(any(Prompt.class))).thenReturn(call("lookupPokemonTeamReference","{}"),stop(),call("calculateBattleDamage","{}"),stop());
        ToolCallback[] tools={tool("lookupPokemonTeamReference"),tool("calculateBattleDamage")};
        var first=c.plan(tools,"").orElseThrow();assertEquals(SpecialistRole.TEAM_PLANNING,first.role());assertEquals("",first.response().getResult().getOutput().getText());
        observe(c,first,"ACTUAL_TEAM_EVIDENCE");
        var second=c.plan(tools,"").orElseThrow();assertEquals(SpecialistRole.DAMAGE_CALCULATION,second.role());
        observe(c,second,"ACTUAL_DAMAGE_EVIDENCE");
        assertFalse(c.plan(tools,"").orElseThrow().response().hasToolCalls());
        var prompts=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(model,times(4)).call(prompts.capture());
        var damage=prompts.getAllValues().get(2);assertTrue(damage.getInstructions().stream().filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                .flatMap(m->m.getResponses().stream()).anyMatch(r->r.responseData().equals("ACTUAL_TEAM_EVIDENCE")));assertFalse(damage.getContents().contains("PRIVATE_"));
        assertSame(parent,ToolExecutionContextHolder.peek());assertTrue(events.stream().anyMatch(e->"DAMAGE_CALCULATION".equals(e.attributes().get("agentRole"))));
    }
    @Test void inventedWriteOrCrossScopeBatchIsRejectedBeforeAnyCallback() {
        for(String args:List.of("{\"request\":{\"gameReleaseId\":\"other-game\"}}","{\"dataReleaseId\":\"wrong\"}","{\"ownerId\":\"other-owner\"}","{\"request\":{\"rulesetVersionId\":\"other\"}}")) {
            var c=new SpecialistAgentCoordinator(context("请计算伤害"),model);when(model.call(any(Prompt.class))).thenReturn(call("calculateBattleDamage",args));
            var read=tool("calculateBattleDamage");assertFalse(c.plan(new ToolCallback[]{read},"").orElseThrow().response().hasToolCalls());verify(read,never()).call(anyString());
        }
        var c=new SpecialistAgentCoordinator(context("分析队伍"),model);when(model.call(any(Prompt.class))).thenReturn(call("previewTeamChanges","{}"));
        assertFalse(c.plan(new ToolCallback[]{tool("lookupPokemonReference"),tool("previewTeamChanges")},"").orElseThrow().response().hasToolCalls());
    }
    @Test void reorderedDuplicateArgumentsCannotLoop() {
        var c=new SpecialistAgentCoordinator(context("查看图鉴"),model);ToolCallback[] tools={tool("lookupPokemonReference")};
        when(model.call(any(Prompt.class))).thenReturn(call("lookupPokemonReference","{\"a\":1,\"b\":2}"),call("lookupPokemonReference","{\"b\":2,\"a\":1}"));
        var first=c.plan(tools,"").orElseThrow();observe(c,first,"{}");
        assertFalse(c.plan(tools,"").orElseThrow().response().hasToolCalls());
        assertTrue(events.stream().anyMatch(e->"TOOL_PLAN_REJECTED".equals(e.attributes().get("status"))));
    }
    @Test void failedToolIsRemovedRatherThanRetriedWithAnotherQuery() {
        var c=new SpecialistAgentCoordinator(context("查看图鉴"),model);ToolCallback[] tools={tool("lookupPokemonReference")};
        when(model.call(any(Prompt.class))).thenReturn(call("lookupPokemonReference","{}"));
        var first=c.plan(tools,"").orElseThrow();observe(c,first,"[tool.error] upstream unavailable");
        assertFalse(c.plan(tools,"").orElseThrow().response().hasToolCalls());verify(model,times(1)).call(any(Prompt.class));
    }
    @Test void callsAndToolsRemainBoundedEvenWhenEveryRequestIsDifferent() {
        var c=new SpecialistAgentCoordinator(context("查看图鉴"),model);ToolCallback[] tools={tool("lookupPokemonReference")};var sequence=new java.util.concurrent.atomic.AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(inv->call("lookupPokemonReference","{\"query\":"+sequence.incrementAndGet()+"}"));
        for(int i=0;i<SpecialistAgentCoordinator.MAX_MODEL_CALLS;i++) {var d=c.plan(tools,"").orElseThrow();assertTrue(d.response().hasToolCalls());observe(c,d,"{}");}
        assertFalse(c.plan(tools,"").orElseThrow().response().hasToolCalls());verify(model,times(6)).call(any(Prompt.class));
    }
    @Test void missingOrWrongIdentitySkipsWithoutConsumingModelBudget() {
        var ctx=context("分析队伍");var c=new SpecialistAgentCoordinator(ctx,model);ToolExecutionContextHolder.clear();assertTrue(c.plan(new ToolCallback[]{tool("lookupPokemonReference")},"").isEmpty());
        ToolExecutionContextHolder.set(new ToolExecutionContext("other",ctx.run().id(),Set.of("trainer"),"t",null,e->{}));
        assertTrue(c.plan(new ToolCallback[]{tool("lookupPokemonReference")},"").isEmpty());verifyNoInteractions(model);
    }
    @Test void cancellationAndGlobalBudgetFailuresPropagateWithoutFallback() {
        var c=new SpecialistAgentCoordinator(context("请计算伤害"),model);var tools=new ToolCallback[]{tool("calculateBattleDamage")};
        Thread.currentThread().interrupt();assertThrows(CancellationException.class,()->c.plan(tools,""));Thread.interrupted();
        when(model.call(any(Prompt.class))).thenThrow(new TaskBudgetExceededException(TaskBudgetExceededException.Reason.CALL_LIMIT));
        assertThrows(TaskBudgetExceededException.class,()->c.plan(tools,""));assertSame(parent,ToolExecutionContextHolder.peek());
    }
    @Test void greetingsAndExplicitMutationsRemainSupervisorOwned() {
        for(String question:List.of("你好","确认修改队伍并保存修改")) {
            var c=new SpecialistAgentCoordinator(context(question),model);assertTrue(c.plan(new ToolCallback[]{tool("lookupPokemonReference")},"").isEmpty());
        }verifyNoInteractions(model);
    }
    @Test void specialistProseNeverBecomesAnAnswerOrEvidence() {
        var c=new SpecialistAgentCoordinator(context("请计算伤害"),model);when(model.call(any(Prompt.class))).thenReturn(stop());
        var result=c.plan(new ToolCallback[]{tool("calculateBattleDamage")},"").orElseThrow();
        assertEquals("",result.response().getResult().getOutput().getText());assertFalse(result.response().hasToolCalls());
    }
    @Test void declinedWritesDoNotDisableReadOnlyDelegation() {
        var c=new SpecialistAgentCoordinator(context("分析队伍并计算伤害，不修改或保存队伍。"),model);
        when(model.call(any(Prompt.class))).thenReturn(call("lookupPokemonTeamReference","{}"));
        assertEquals(SpecialistRole.TEAM_PLANNING,c.plan(new ToolCallback[]{tool("lookupPokemonTeamReference")},"").orElseThrow().role());
    }
}
