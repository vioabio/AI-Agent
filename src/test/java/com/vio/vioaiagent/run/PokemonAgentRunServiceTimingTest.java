package com.vio.vioaiagent.run;

import com.vio.vioaiagent.tools.governance.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PokemonAgentRunServiceTimingTest {
    @TempDir Path directory;
    private static CreateRunCommand command(String prompt){return new CreateRunCommand("owner",null,null,prompt,"sv-1","singles","rules-1","zh-CN",null,null,Duration.ofSeconds(20));}
    private static Map<?,?> usage(AgentEvent event){return (Map<?,?>)event.payload().get("modelUsage");}
    @Test void bufferedReadinessAndToolTimingsSurviveSqliteWithoutPretendingThereWasAnSdkTextStream() {
        var model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("答复")))));
        var delegate=new DefaultRunExecutor(model,new org.springframework.ai.tool.ToolCallback[0],null,null,null,null,false);
        RunExecutor executor=context->{
            var parent=ToolExecutionContextHolder.current();
            for(int i=0;i<2;i++)parent.eventSink().accept(new ToolGovernanceEvent("tool.deduplicated",parent.runId(),"lookup","same-call",Instant.now(),
                    Map.of("status","SUCCEEDED","timing",ToolAttemptTiming.bypass("CACHED_RESULT"))));
            return delegate.execute(context);
        };
        var events=new InMemoryEventRepository();String path=directory.resolve("history.sqlite").toString();
        var history=new SqliteLocalHistoryRepository(path,new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        var service=new PokemonAgentRunService(new InMemoryRunRepository(),events,history,history,executor,Executors.newSingleThreadExecutor(),Executors.newSingleThreadScheduledExecutor());
        try {
            var run=service.createRun(command("你好"));var terminal=RunTestAwait.awaitTerminalEvent(events,run.runId());assertEquals(AgentEventType.RUN_COMPLETED,terminal.type());
            var response=(Map<?,?>)usage(terminal).get("responseTiming");assertEquals("BUFFERED",response.get("answerPath"));assertEquals(false,response.get("deliveryMeasured"));
            assertTrue((Long)response.get("firstAnswerReadyMs")>=(Long)response.get("dispatchWaitMs"));
            assertEquals(1,((List<?>)usage(terminal).get("toolTimings")).size());
            for(var call:(List<?>)usage(terminal).get("calls"))assertNull(((Map<?,?>)call).get("firstTextDeltaMs"));
            var reopened=new SqliteLocalHistoryRepository(path,new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
            var answer=reopened.findByConversationForUser(run.conversationId(),"owner").getLast();
            var attempt=(Map<?,?>)((List<?>)answer.metadata().get("modelUsageAttempts")).getFirst();
            assertEquals("BUFFERED",((Map<?,?>)attempt.get("responseTiming")).get("answerPath"));assertEquals(1,((List<?>)attempt.get("toolTimings")).size());
            assertTrue(reopened.findByConversationForUser(run.conversationId(),"other-owner").isEmpty());
        } finally {service.shutdown();}
    }
    @Test void dispatchWaitUsesTaskRegistrationAndStopsOnlyWhenTheWorkerEnters() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var events=new InMemoryEventRepository();
        RunExecutor executor=context->{if(context.run().prompt().equals("hold")){entered.countDown();release.await();}return "答复";};
        var service=new PokemonAgentRunService(new InMemoryRunRepository(),events,new InMemoryConversationRepository(),new InMemoryChatMessageRepository(),executor,
                Executors.newSingleThreadExecutor(),Executors.newSingleThreadScheduledExecutor());
        try {
            service.createRun(command("hold"));assertTrue(entered.await(2,TimeUnit.SECONDS));
            var queued=service.createRun(command("answer"));Thread.sleep(35);release.countDown();
            var timing=(Map<?,?>)usage(RunTestAwait.awaitTerminalEvent(events,queued.runId())).get("responseTiming");
            assertTrue((Long)timing.get("dispatchWaitMs")>=25);assertTrue((Long)timing.get("firstAnswerReadyMs")>=(Long)timing.get("dispatchWaitMs"));
        } finally {release.countDown();service.shutdown();}
    }
}
