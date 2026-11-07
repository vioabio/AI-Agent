package com.vio.vioaiagent.run;

import com.vio.vioaiagent.config.ContextBudgetProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PokemonAgentRunServiceConstraintsTest {
    private CreateRunCommand command(String id) {return new CreateRunCommand("owner",null,id,"介绍水属性培养建议","sv-1","singles","rules-1","zh-CN",null,null,Duration.ofSeconds(15));}
    private ConversationConstraints value(long revision,String notes) {return new ConversationConstraints(revision,ConversationConstraints.ResponseStyle.CONCISE,ConversationConstraints.TrainingBudget.LOW,true,notes);}
    @Test void queuedRunUsesItsOwnSnapshotAndNewRunUsesNewPreferences() throws Exception {
        var conversations=new InMemoryConversationRepository();var messages=new InMemoryChatMessageRepository();var events=new InMemoryEventRepository();
        var worker=Executors.newSingleThreadExecutor();var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        worker.submit(()->{held.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(held.await(2,TimeUnit.SECONDS));
        List<ConversationConstraints> seen=new CopyOnWriteArrayList<>();
        var service=new PokemonAgentRunService(new InMemoryRunRepository(),events,conversations,messages,c->{seen.add(c.constraints());return "训练建议";},worker,Executors.newSingleThreadScheduledExecutor());
        try {
            service.saveConversationConstraints("c","owner",value(0,"旧要求"));
            var run=service.createRun(command("c"));service.saveConversationConstraints("c","owner",value(1,"新要求"));release.countDown();
            assertEquals(AgentEventType.RUN_COMPLETED,RunTestAwait.awaitTerminalEvent(events,run.runId()).type());
            assertEquals("旧要求",seen.getFirst().notes());
            var answer=service.messages("c","owner").stream().filter(m->m.runId().equals(run.runId()) && m.role().equals("assistant")).findFirst().orElseThrow();
            assertEquals(1,ConversationConstraints.fromSnapshot(answer.metadata().get("conversationConstraints")).revision());
            var next=service.createRun(command("c"));RunTestAwait.awaitTerminalEvent(events,next.runId());assertEquals("新要求",seen.getLast().notes());
            assertThrows(RunConflictException.class,()->service.saveConversationConstraints("c","owner",value(1,"过期")));
            assertThrows(RunNotFoundException.class,()->service.conversationConstraints("c","other"));
            assertThrows(RunNotFoundException.class,()->service.saveConversationConstraints("c","other",value(0,"越权")));
        } finally {release.countDown();service.shutdown();}
    }
    @Test void explicitConstraintsReachEveryModelRequestAfterOlderHistoryIsTrimmed() {
        var conversations=new InMemoryConversationRepository();var messages=new InMemoryChatMessageRepository();var events=new InMemoryEventRepository();
        var model=mock(ChatModel.class);List<Prompt> requests=new CopyOnWriteArrayList<>();
        when(model.call(any(Prompt.class))).thenAnswer(inv->{requests.add(inv.getArgument(0));return new ChatResponse(List.of(new Generation(new AssistantMessage("训练建议"))));});
        var executor=new DefaultRunExecutor(model,new org.springframework.ai.tool.ToolCallback[0],null,null,null,null,false);
        var limits=new ContextBudgetProperties(32768,2048,4096,1,125);executor.setContextBudgetProperties(limits);
        var service=new PokemonAgentRunService(new InMemoryRunRepository(),events,conversations,messages,executor,Executors.newSingleThreadExecutor(),Executors.newSingleThreadScheduledExecutor());
        service.setContextBudgetProperties(limits);
        try {
            service.saveConversationConstraints("c","owner",value(0,"只按新手能理解的方式解释"));
            for(int i=0;i<12;i++) {
                Instant at=Instant.now().minusSeconds(100-i);String old="old-"+i;
                messages.save(new ChatMessage("u"+i,"c",old,"owner","user","历史问题"+i,at,Map.of()));
                messages.save(new ChatMessage("a"+i,"c",old,"owner","assistant","历史答复"+i,at.plusMillis(1),Map.of()));
            }
            var run=service.createRun(command("c"));var done=RunTestAwait.awaitTerminalEvent(events,run.runId());assertEquals(AgentEventType.RUN_COMPLETED,done.type());
            assertTrue(requests.size()>=2);
            for(Prompt request:requests) {
                var text=request.getContents();assertTrue(text.contains("只按新手能理解的方式解释"));assertTrue(text.contains("优先避开传说宝可梦"));
                assertFalse(text.contains("avoidLegendary"));assertFalse(text.contains("USER_CONFIRMED"));
                assertFalse(text.contains("历史问题0"));
                assertTrue(request.getInstructions().stream().filter(m->m.getText().contains("只按新手能理解的方式解释")).allMatch(m->m instanceof UserMessage));
            }
            var usage=(Map<?,?>)done.payload().get("modelUsage");assertEquals(11,((Map<?,?>)usage.get("contextBudget")).get("omittedHistoryTurns"));
        } finally {service.shutdown();}
    }
}
