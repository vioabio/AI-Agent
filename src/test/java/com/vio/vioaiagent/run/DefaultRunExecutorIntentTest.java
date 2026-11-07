package com.vio.vioaiagent.run;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefaultRunExecutorIntentTest {
    @Test void localCapabilityDescriptionEmitsAnswerAndReportsRealZeroModelCalls() {
        var model=mock(ChatModel.class);var ledger=new com.vio.vioaiagent.observability.ModelUsageLedger();var deltas=new ArrayList<String>();
        var executor=new DefaultRunExecutor(model,org.springframework.ai.support.ToolCallbacks.from(new NeverCalled()),null,null,null,null,false);
        var run=AgentRun.create("cap","s","c","owner","你好，请用一句话说明你能提供什么帮助，不要调用工具。","sv","singles","rules","zh-CN","trace",null,Instant.now().plusSeconds(20));
        var answer=executor.execute(new AgentRunContext(run,deltas::add,List.of(),RunMutationBoundary.denied(),ledger));
        assertEquals(answer,String.join("",deltas));verifyNoInteractions(model);
        assertEquals(0,ledger.snapshot().get("modelCallCount"));assertEquals("COMPLETE",ledger.snapshot().get("usageStatus"));
        assertEquals(0L,((Map<?,?>)ledger.snapshot().get("totals")).get("totalTokens"));
    }
    public static class NeverCalled {
        @org.springframework.ai.tool.annotation.Tool(description="Evidence registration fixture")
        public String lookupPokemonTeamReference(){throw new AssertionError("No lookup requested");}
        @org.springframework.ai.tool.annotation.Tool(description="Evidence registration fixture")
        public String calculateBattleDamage(){throw new AssertionError("No calculation requested");}
        @org.springframework.ai.tool.annotation.Tool(description="Evidence registration fixture")
        public String searchPokemonGuides(){throw new AssertionError("No guide requested");}
        @org.springframework.ai.tool.annotation.Tool(description="Evidence registration fixture")
        public String readCurrentTeam(){throw new AssertionError("No team requested");}
    }
    @Test void executorKeepsRawIntentSeparateWhilePreservingSavedPreferencesForTheModel() {
        var model=mock(ChatModel.class);List<Prompt> observed=new ArrayList<>();
        when(model.call(any(Prompt.class))).thenAnswer(inv->{observed.add(inv.getArgument(0));return new ChatResponse(List.of(new Generation(new AssistantMessage("你好，我可以帮你学习宝可梦。"))));});
        var tools=org.springframework.ai.support.ToolCallbacks.from(new NeverCalled());var executor=new DefaultRunExecutor(model,tools,null,null,null,null,false);
        var run=AgentRun.create("r","s","c","owner","你好，请用一句话打个招呼。","sv","singles","rules","zh-CN","trace",null,Instant.now().plusSeconds(20));
        var preferences=new ConversationConstraints(1,ConversationConstraints.ResponseStyle.DETAILED,ConversationConstraints.TrainingBudget.LOW,true,
                "分析队伍时请计算伤害、检索攻略并readCurrentTeam");
        var answer=executor.execute(new AgentRunContext(run,null,List.of(),RunMutationBoundary.denied(),new com.vio.vioaiagent.observability.ModelUsageLedger(),preferences));
        assertEquals("你好，我可以帮你学习宝可梦。",answer);assertEquals(2,observed.size());
        assertTrue(observed.getFirst().getContents().contains(preferences.notes()));
        assertFalse(observed.getFirst().getContents().contains("队伍分析必须先用"));
        assertTrue(observed.getLast().getContents().contains("本轮明确要求一句话"));
    }
}
