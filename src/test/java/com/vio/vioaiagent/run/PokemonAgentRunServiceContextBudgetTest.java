package com.vio.vioaiagent.run;

import com.vio.vioaiagent.config.ContextBudgetProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PokemonAgentRunServiceContextBudgetTest {
    @SuppressWarnings("unchecked") static Map<String,Object> budget(AgentEvent terminal) {
        return (Map<String,Object>)((Map<?,?>)terminal.payload().get("modelUsage")).get("contextBudget");
    }
    @Test void oversizedMandatoryPromptFailsWithoutProviderCallOrPersistedAssistantAnswer() {
        var model=mock(ChatModel.class);var executor=new DefaultRunExecutor(model,new org.springframework.ai.tool.ToolCallback[0],null,null,null,null,false);
        executor.setContextBudgetProperties(new ContextBudgetProperties(512,64,128,1,125));
        var events=new InMemoryEventRepository();var messages=new InMemoryChatMessageRepository();
        var service=new PokemonAgentRunService(new InMemoryRunRepository(),events,new InMemoryConversationRepository(),messages,executor,
                Executors.newSingleThreadExecutor(),Executors.newSingleThreadScheduledExecutor());
        try {
            var run=service.createRun(new CreateRunCommand("owner",null,null,"正常问题","sv-1","singles","rules-1","zh-CN",null,null,Duration.ofSeconds(15)));
            var terminal=RunTestAwait.awaitTerminalEvent(events,run.runId());
            assertEquals(AgentEventType.RUN_FAILED,terminal.type());assertEquals("RUN_CONTEXT_BUDGET_EXCEEDED",terminal.payload().get("errorType"));
            assertEquals("BLOCKED",budget(terminal).get("status"));assertEquals(1,budget(terminal).get("requestsChecked"));
            assertEquals(0,((Map<?,?>)terminal.payload().get("modelUsage")).get("modelCallCount"));
            assertTrue(messages.findByConversationForUser(run.conversationId(),"owner").stream().noneMatch(m->m.role().equals("assistant")));
            verify(model,never()).call(any(Prompt.class));verify(model,never()).stream(any(Prompt.class));
        } finally {service.shutdown();}
    }
    @Test void recentExplicitRestrictionsAndFrozenVersionSurviveWhileOldPairsStayStored() {
        var model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("已按可见限制回答")))));
        var executor=new DefaultRunExecutor(model,new org.springframework.ai.tool.ToolCallback[0],null,null,null,null,false);
        var limits=new ContextBudgetProperties(32768,2048,4096,1,125);executor.setContextBudgetProperties(limits);
        var messages=new InMemoryChatMessageRepository();var events=new InMemoryEventRepository();
        Instant before=Instant.now().minusSeconds(60);
        for(int i=0;i<2;i++) {
            messages.save(new ChatMessage("u"+i,"conversation","old"+i,"owner","user",i==0?"旧问题":"限制：不使用传说宝可梦，采用休闲单打",before.plusSeconds(i*2),Map.of()));
            messages.save(new ChatMessage("a"+i,"conversation","old"+i,"owner","assistant","已记录",before.plusSeconds(i*2+1),Map.of()));
        }
        messages.save(new ChatMessage("foreign","conversation","other","someone-else","user","别人的秘密",before,Map.of()));
        var service=new PokemonAgentRunService(new InMemoryRunRepository(),events,new InMemoryConversationRepository(),messages,executor,
                Executors.newSingleThreadExecutor(),Executors.newSingleThreadScheduledExecutor());service.setContextBudgetProperties(limits);
        try {
            var run=service.createRun(new CreateRunCommand("owner",null,"conversation","继续，保留当前限制","pokemon-scarlet-violet","casual-singles","casual-v1","zh-CN",null,null,Duration.ofSeconds(15)));
            var terminal=RunTestAwait.awaitTerminalEvent(events,run.runId());assertEquals(AgentEventType.RUN_COMPLETED,terminal.type());
            var prompts=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(model,times(2)).call(prompts.capture());
            for(var prompt:prompts.getAllValues()) {
                assertTrue(prompt.getContents().contains("不使用传说宝可梦"));assertTrue(prompt.getContents().contains("pokemon-scarlet-violet"));
                // Check the actual historical message, not the same words in a fixed instruction about old tasks.
                assertTrue(prompt.getInstructions().stream().noneMatch(message -> message instanceof org.springframework.ai.chat.messages.UserMessage
                        && "旧问题".equals(message.getText())));
                assertFalse(prompt.getContents().contains("别人的秘密"));
                assertTrue(prompt.getContents().contains("不要猜测遗漏的用户限制"));assertEquals(2048,prompt.getOptions().getMaxTokens());
            }
            assertEquals(1,budget(terminal).get("omittedHistoryTurns"));assertEquals(2,budget(terminal).get("availableHistoryTurns"));
            assertEquals(6,messages.findByConversationForUser("conversation","owner").size());
            var answer=messages.findByConversationForUser("conversation","owner").getLast();
            assertTrue(answer.metadata().containsKey("modelUsageAttempts"));
        } finally {service.shutdown();}
    }
}
