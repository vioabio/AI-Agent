package com.vio.vioaiagent.run;

import com.vio.vioaiagent.config.EvaluationToolPolicy;
import com.vio.vioaiagent.observability.ModelUsageLedger;
import com.vio.vioaiagent.pokemon.team.TeamWorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.*;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefaultRunExecutorEvaluationTest {
    EvaluationToolPolicy policy(String mode){var e=new MockEnvironment().withProperty("vio.evaluation.tool-mode",mode).withProperty("vio.local-accounts.enabled","true");e.setActiveProfiles("local","evaluation");return new EvaluationToolPolicy(e);}
    ToolCallback tool(String name){var t=mock(ToolCallback.class);when(t.getToolDefinition()).thenReturn(ToolDefinition.builder().name(name).description("test").inputSchema("{\"type\":\"object\",\"properties\":{}}").build());return t;}
    AgentRunContext context(ModelUsageLedger usage) {
        return new AgentRunContext(AgentRun.create("run-eval","session","conversation","trainer","你好，请用一句话说明你能提供什么帮助，不要调用工具。",
                "pokemon-scarlet-violet","casual-singles","casual-v1","zh-CN","trace",null,Instant.now().plusSeconds(30)),null,List.of(),RunMutationBoundary.denied(),usage);
    }
    ChatModel model(List<Prompt> seen) {
        var model=mock(ChatModel.class);when(model.getDefaultOptions()).thenReturn(ChatOptions.builder().model("fixture").maxTokens(512).temperature(0.0).build());
        when(model.call(any(Prompt.class))).thenAnswer(call->{seen.add(call.getArgument(0));return new ChatResponse(List.of(new Generation(new AssistantMessage("你好，我可以帮助你学习宝可梦。"))));});return model;
    }
    @Test void modelOnlyHasNoCallbacksOrMcpAndUsesObservedBudgetedModelInsteadOfLocalShortcut() {
        var seen=new ArrayList<Prompt>();var provider=mock(ToolCallbackProvider.class);var workflow=mock(TeamWorkflowService.class);
        var executor=new DefaultRunExecutor(model(seen),new ToolCallback[]{tool("previewTeamChanges")},provider,null,null,null,false);
        executor.setTeamWorkflow(workflow);executor.setEvaluationToolPolicy(policy("model-only"));var ledger=new ModelUsageLedger();
        assertTrue(executor.execute(context(ledger)).contains("你好"));assertEquals(2,seen.size());
        for(var prompt:seen)if(prompt.getOptions() instanceof ToolCallingChatOptions options)assertTrue(options.getToolCallbacks()==null||options.getToolCallbacks().isEmpty());
        assertEquals(2,ledger.snapshot().get("modelCallCount"));verifyNoInteractions(provider,workflow);
    }
    @Test void guideArmAdvertisesOnlyTheSelectedExistingGovernedCallback() {
        var seen=new ArrayList<Prompt>();var guide=tool("searchPokemonGuides");var bad=tool("previewTeamChanges");
        var executor=new DefaultRunExecutor(model(seen),new ToolCallback[]{guide,bad},null,null,null,null,false);
        executor.setEvaluationToolPolicy(policy("guides-only"));executor.execute(context(new ModelUsageLedger()));
        var options=(ToolCallingChatOptions)seen.getFirst().getOptions();assertEquals(List.of(guide),options.getToolCallbacks());
        verify(bad,never()).call(anyString());
    }
    @Test void hallucinatedExcludedWriteCannotInvokeTheRegisteredWriteCallback() {
        var seen=new ArrayList<Prompt>();var model=model(seen);var count=new AtomicInteger();var bad=tool("previewTeamChanges");
        when(model.call(any(Prompt.class))).thenAnswer(call->{
            if(count.getAndIncrement()==0)return new ChatResponse(List.of(new Generation(new AssistantMessage("",Map.of(),List.of(new AssistantMessage.ToolCall("unexpected","function","previewTeamChanges","{}"))))));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("本次无法修改队伍。"))));
        });
        var executor=new DefaultRunExecutor(model,new ToolCallback[]{bad},null,null,null,null,false);executor.setEvaluationToolPolicy(policy("model-only"));
        try{executor.execute(context(new ModelUsageLedger()));}catch(IllegalStateException expected){ /* An unresolvable tool call may fail the Run. */ }
        verify(bad,never()).call(anyString());assertTrue(count.get()>0);
    }
    @Test void missingModelDoesNotUseUnobservedLegacyFallback() {
        var game=mock(com.vio.vioaiagent.app.GameApp.class);var executor=new DefaultRunExecutor(null,new ToolCallback[0],null,null,game,null,false);
        executor.setEvaluationToolPolicy(policy("model-only"));assertThrows(IllegalStateException.class,()->executor.execute(context(new ModelUsageLedger())));verifyNoInteractions(game);
    }
}
