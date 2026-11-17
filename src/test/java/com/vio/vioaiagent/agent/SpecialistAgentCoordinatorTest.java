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
    @Test void explicitMoveAttackCalculationSelectsDamageRoleWithoutDamageKeyword() {
        for (boolean enabled : List.of(false,true)) {
            var chat=mock(ChatModel.class);
            var coordinator=new SpecialistAgentCoordinator(context("按默认50级、Hardy、0 EV和31 IV，计算杰尼龟用撞击攻击杰尼龟，无道具、无天气及场地。"),chat);
            coordinator.setSkillsEnabled(enabled);
            when(chat.call(any(Prompt.class))).thenReturn(call("calculateBattleDamage","{}"));
            var decision=coordinator.plan(new ToolCallback[]{tool("lookupPokemonReference"),tool("calculateBattleDamage")},"").orElseThrow();
            assertEquals(SpecialistRole.DAMAGE_CALCULATION,decision.role());
            assertTrue(Arrays.stream(decision.tools()).anyMatch(tool->tool.getToolDefinition().name().equals("calculateBattleDamage")));
        }
    }
    @Test void statCalculationsAndAttackDescriptionsRemainResearchQuestions() {
        for (String question : List.of("计算攻击种族值的总和", "计算宝可梦杰尼龟50级的实际能力值需要哪些条件？", "介绍宝可梦杰尼龟用撞击攻击时的动画",
                "不要计算杰尼龟用撞击攻击杰尼龟，只查询图鉴。", "无需再计算宝可梦杰尼龟用撞击攻击杰尼龟，只介绍动画。")) {
            var chat=mock(ChatModel.class);
            when(chat.call(any(Prompt.class))).thenReturn(call("searchPokemonGuides","{}"));
            var decision=new SpecialistAgentCoordinator(context(question),chat)
                    .plan(new ToolCallback[]{tool("lookupPokemonReference"),tool("searchPokemonGuides"),tool("calculateBattleDamage")},"").orElseThrow();
            assertEquals(SpecialistRole.POKEMON_RESEARCH,decision.role());
            assertTrue(Arrays.stream(decision.tools()).noneMatch(tool->tool.getToolDefinition().name().equals("calculateBattleDamage")));
        }
    }
    @Test void serviceWindowOmissionIsVisibleWithoutSendingFullRoutingHistoryToTheModel() {
        var base = context("我想围绕一个核心组队，怎样检查功能角色是否重叠？");
        var ledger = new ModelUsageLedger(); ledger.historySource(Map.of("availableTurns",9,"selectedTurns",1,"omittedTurns",8));
        var ctx = new AgentRunContext(base.run(),null,List.of(new UserMessage("当前短历史"),new AssistantMessage("确认")),
                base.mutations(),ledger,base.constraints(),List.of(new UserMessage("PRIVATE_OLD_ROUTING_ONLY"),new AssistantMessage("旧信息")));
        var chat = mock(ChatModel.class); var coordinator = new SpecialistAgentCoordinator(ctx,chat);
        coordinator.setContextAssembly(true,com.vio.vioaiagent.config.ContextBudgetProperties.defaults());
        when(chat.call(any(Prompt.class))).thenAnswer(invocation -> {
            String text = ((Prompt)invocation.getArgument(0)).getContents();
            assertTrue(text.contains("程序历史窗口已省略较早的8轮"));
            assertTrue(text.contains("当前短历史")); assertFalse(text.contains("PRIVATE_OLD_ROUTING_ONLY")); return stop();
        });
        coordinator.plan(new ToolCallback[]{tool("searchPokemonGuides")},"");
        var event = events.stream().filter(e -> e.eventType().equals("agent.delegation.context")).findFirst().orElseThrow();
        assertEquals("CONTEXT_HISTORY_TRIMMED",event.attributes().get("status"));
        assertEquals(8,event.attributes().get("sourceOmittedTurns"));
        assertEquals(0,event.attributes().get("assemblyOmittedTurns"));
        assertEquals(1,event.attributes().get("selectedHistoryTurns"));
    }
    @Test void assemblyOmissionIsReportedSeparatelyFromThePersistedHistoryWindow() {
        var base = context("分析队伍角色"); var ledger = new ModelUsageLedger();
        ledger.historySource(Map.of("availableTurns",10,"selectedTurns",2,"omittedTurns",8));
        var ctx = new AgentRunContext(base.run(),null,List.of(new UserMessage("早消息"),new AssistantMessage("早回答"),
                new UserMessage("近消息"),new AssistantMessage("近回答")),base.mutations(),ledger);
        var chat = mock(ChatModel.class);var coordinator = new SpecialistAgentCoordinator(ctx,chat);
        coordinator.setContextAssembly(true,new com.vio.vioaiagent.config.ContextBudgetProperties(32768,2048,4096,1,125));
        when(chat.call(any(Prompt.class))).thenAnswer(invocation -> {
            String text=((Prompt)invocation.getArgument(0)).getContents();assertFalse(text.contains("早消息"));assertTrue(text.contains("近消息"));return stop();
        });
        coordinator.plan(new ToolCallback[]{tool("searchPokemonGuides")},"");
        var event=events.stream().filter(e->e.eventType().equals("agent.delegation.context")).findFirst().orElseThrow();
        assertEquals(8,event.attributes().get("sourceOmittedTurns"));assertEquals(1,event.attributes().get("assemblyOmittedTurns"));
        assertEquals(1,event.attributes().get("selectedHistoryTurns"));
    }
    @Test void statMeaningResearchNeverExposesSpeciesReadsWithEitherSkillSetting() {
        for (boolean enabled : List.of(false, true)) {
            var chat = mock(ChatModel.class);
            var coordinator = new SpecialistAgentCoordinator(context("大嘴鸥的种族值能否直接说明我这只的实际速度？"), chat);
            coordinator.setSkillsEnabled(enabled);
            when(chat.call(any(Prompt.class))).thenAnswer(invocation -> {
                var options = (ToolCallingChatOptions) ((Prompt) invocation.getArgument(0)).getOptions();
                assertEquals(List.of("searchPokemonGuides"), options.getToolCallbacks().stream().map(t -> t.getToolDefinition().name()).toList());
                return call("lookupPokemonReference", "{\"query\":\"奇鲁莉安\"}");
            });
            var decision = coordinator.plan(new ToolCallback[]{tool("searchPokemonGuides"), tool("lookupPokemonReference")}, "").orElseThrow();
            assertFalse(decision.response().getResult().getOutput().hasToolCalls());
            verify(chat, times(1)).call(any(Prompt.class));
        }
    }
    @Test void generalPlanningRejectsSpecialistRequestForPrivateTeamList(){
        var coordinator=new SpecialistAgentCoordinator(context("我想围绕一个核心组成队伍，如何检查功能角色是否重复？"),model);
        coordinator.setSkillsEnabled(true);
        when(model.call(any(Prompt.class))).thenAnswer(inv->{
            var prompt=(Prompt)inv.getArgument(0);assertTrue(prompt.getContents().contains("team-core-planning@1"));
            var options=(ToolCallingChatOptions)prompt.getOptions();
            assertEquals(Set.of("searchPokemonGuides"),options.getToolCallbacks().stream().map(t->t.getToolDefinition().name()).collect(java.util.stream.Collectors.toSet()));
            return call("listCurrentTeams","{}");
        });
        var decision=coordinator.plan(new ToolCallback[]{tool("searchPokemonGuides"),tool("listCurrentTeams"),tool("readCurrentTeam")},"").orElseThrow();
        assertFalse(decision.response().hasToolCalls());
        assertTrue(events.stream().anyMatch(event->"TOOL_PLAN_REJECTED".equals(event.attributes().get("status"))));
    }
    @Test void gymFollowupUsesDialogueStateEvenWhenOldTargetIsOutsideModelHistory(){
        var base=context("这个队伍如何应对");
        var dialogue=List.<Message>of(new UserMessage("查看奇树重赛资料"),new AssistantMessage("收到"));
        var bounded=new ArrayList<Message>();
        for(int turn=0;turn<9;turn++){
            bounded.add(new UserMessage("填充对话"+turn));bounded.add(new AssistantMessage("收到"));
        }
        var ctx=new AgentRunContext(base.run(),null,bounded,base.mutations(),base.modelUsage(),base.constraints(),dialogue);
        var chat=mock(ChatModel.class);var coordinator=new SpecialistAgentCoordinator(ctx,chat);coordinator.setSkillsEnabled(true);
        coordinator.setContextAssembly(true,com.vio.vioaiagent.config.ContextBudgetProperties.defaults());
        when(chat.call(any(Prompt.class))).thenAnswer(inv->{
            var prompt=(Prompt)inv.getArgument(0);
            assertTrue(prompt.getContents().contains("gym-challenge@1"));
            assertTrue(prompt.getContents().contains("gymId=iono, phase=rematch, defaultStage=false"));
            assertFalse(prompt.getContents().contains("填充对话0"));assertTrue(prompt.getContents().contains("填充对话8"));
            var options=(ToolCallingChatOptions)prompt.getOptions();
            assertEquals(Set.of("analyzeSavedTeamForGym"),options.getToolCallbacks().stream().map(t->t.getToolDefinition().name()).collect(java.util.stream.Collectors.toSet()));
            return stop();
        });
        coordinator.plan(new ToolCallback[]{tool("analyzeSavedTeamForGym"),tool("checkPokemonTeamBuild"),tool("previewTeamChanges")},"");
        verify(chat).call(any(Prompt.class));
    }
    @Test void generalTeamTaskDoesNotCarryOldGymInstructions(){
        var base=context("分析我的队伍");
        var ctx=new AgentRunContext(base.run(),null,List.of(new UserMessage("奇树重赛资料"),new AssistantMessage("收到")));
        var chat=mock(ChatModel.class);var coordinator=new SpecialistAgentCoordinator(ctx,chat);coordinator.setSkillsEnabled(true);
        when(chat.call(any(Prompt.class))).thenAnswer(inv->{
            var prompt=(Prompt)inv.getArgument(0);
            assertTrue(prompt.getContents().contains("saved-team-review@1"));
            assertFalse(prompt.getContents().contains("Program-resolved conversation target"));
            return stop();
        });
        coordinator.plan(new ToolCallback[]{tool("checkPokemonTeamBuild"),tool("analyzeSavedTeamForGym")},"");
        verify(chat).call(any(Prompt.class));
    }
    @Test void assistantOnlyAndAmbiguousGymHistoryCannotSelectChallengeSkill(){
        for(var history:List.of(List.<Message>of(new AssistantMessage("奇树重赛")),
                List.<Message>of(new UserMessage("奇树和阿枫的队伍"),new AssistantMessage("收到")))){
            var base=context("这个队伍如何应对");var ctx=new AgentRunContext(base.run(),null,history);
            var chat=mock(ChatModel.class);var coordinator=new SpecialistAgentCoordinator(ctx,chat);coordinator.setSkillsEnabled(true);
            when(chat.call(any(Prompt.class))).thenAnswer(inv->{
                assertTrue(((Prompt)inv.getArgument(0)).getContents().contains("saved-team-review@1"));return stop();
            });
            coordinator.plan(new ToolCallback[]{tool("checkPokemonTeamBuild"),tool("analyzeSavedTeamForGym")},"");
            verify(chat).call(any(Prompt.class));
        }
    }
    @Test void newUserSelectionReplacesOldGymAndAnotherConversationHasNoInheritedTarget(){
        var base=context("这个队伍如何应对");
        var ctx=new AgentRunContext(base.run(),null,List.of(new UserMessage("奇树重赛"),new AssistantMessage("收到"),new UserMessage("改看阿枫初战"),new AssistantMessage("收到")));
        var chat=mock(ChatModel.class);var coordinator=new SpecialistAgentCoordinator(ctx,chat);coordinator.setSkillsEnabled(true);
        when(chat.call(any(Prompt.class))).thenAnswer(inv->{
            var text=((Prompt)inv.getArgument(0)).getContents();
            assertTrue(text.contains("gymId=katy, phase=initial"));assertFalse(text.contains("gymId=iono"));return stop();
        });
        coordinator.plan(new ToolCallback[]{tool("analyzeSavedTeamForGym")},"");verify(chat).call(any(Prompt.class));
        var emptyChat=mock(ChatModel.class);var empty=new SpecialistAgentCoordinator(context("这个队伍如何应对"),emptyChat);empty.setSkillsEnabled(true);
        when(emptyChat.call(any(Prompt.class))).thenAnswer(inv->{assertTrue(((Prompt)inv.getArgument(0)).getContents().contains("saved-team-review@1"));return stop();});
        empty.plan(new ToolCallback[]{tool("checkPokemonTeamBuild"),tool("analyzeSavedTeamForGym")},"");verify(emptyChat).call(any(Prompt.class));
    }
    @Test void namedGymSkillExposesChallengeToolThroughNormalCoordinator(){
        var chat=mock(ChatModel.class);
        var coordinator=new SpecialistAgentCoordinator(context("我的队伍如何应对奇树初战"),chat);
        coordinator.setSkillsEnabled(true);
        when(chat.call(any(Prompt.class))).thenAnswer(inv->{
            var prompt=(Prompt)inv.getArgument(0);
            assertTrue(prompt.getContents().contains("gym-challenge@1"));
            var options=(ToolCallingChatOptions)prompt.getOptions();
            assertEquals(Set.of("analyzeSavedTeamForGym"),options.getToolCallbacks().stream().map(t->t.getToolDefinition().name()).collect(java.util.stream.Collectors.toSet()));
            return stop();
        });
        coordinator.plan(new ToolCallback[]{tool("analyzeSavedTeamForGym"),tool("previewTeamChanges"),tool("lookupCompetitiveTrends")},"");
        verify(chat).call(any(Prompt.class));
    }
    @Test void englishCourtesyDoesNotReplaceTrainingSkillWithCompetitiveTools(){
        var chat=mock(ChatModel.class);
        var coordinator=new SpecialistAgentCoordinator(context("Could you explain 宝可梦培养？"),chat);
        coordinator.setSkillsEnabled(true);
        when(chat.call(any(Prompt.class))).thenAnswer(inv->{
            var prompt=(Prompt)inv.getArgument(0);assertTrue(prompt.getContents().contains("training-build-review@1"));
            var options=(ToolCallingChatOptions)prompt.getOptions();
            assertEquals(Set.of("lookupPokemonLearnset"),options.getToolCallbacks().stream().map(t->t.getToolDefinition().name()).collect(java.util.stream.Collectors.toSet()));
            return stop();
        });
        coordinator.plan(new ToolCallback[]{tool("lookupPokemonLearnset"),tool("lookupCompetitiveTrends")},"");
        verify(chat).call(any(Prompt.class));
    }
    @Test void contextAssemblyCanBeEnabledWithoutChangingToolAuthority() {
        var chat=mock(ChatModel.class);var c=new SpecialistAgentCoordinator(context("查询图鉴"),chat);
        c.setContextAssembly(true,com.vio.vioaiagent.config.ContextBudgetProperties.defaults());
        when(chat.call(any(Prompt.class))).thenAnswer(inv->{
            var prompt=(Prompt)inv.getArgument(0);assertTrue(prompt.getContents().contains("Authoritative current task"));return stop();
        });
        c.plan(new ToolCallback[]{tool("lookupPokemonReference")},"");verify(chat).call(any(Prompt.class));
    }

    @Test void enabledSkillsAddTaskInstructionsWithoutGrantingWriteTools() {
        var chat=mock(ChatModel.class);
        var coordinator=new SpecialistAgentCoordinator(context("分析我的队伍"),chat);
        coordinator.setSkillsEnabled(true);
        when(chat.call(any(Prompt.class))).thenAnswer(inv->{
            var prompt=(Prompt)inv.getArgument(0);
            assertTrue(prompt.getContents().contains("saved-team-review@1"));
            var opts=(ToolCallingChatOptions)prompt.getOptions();
            assertFalse(opts.getToolCallbacks().stream().anyMatch(t->t.getToolDefinition().name().equals("previewTeamChanges")));
            return stop();
        });
        coordinator.plan(new ToolCallback[]{tool("lookupPokemonReference"),tool("previewTeamChanges")},"");
        verify(chat).call(any(Prompt.class));
    }

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
