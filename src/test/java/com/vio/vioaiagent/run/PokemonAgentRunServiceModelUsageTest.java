package com.vio.vioaiagent.run;

import com.vio.vioaiagent.observability.*;
import com.vio.vioaiagent.tools.governance.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.api.OpenAiApi;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PokemonAgentRunServiceModelUsageTest {
    @Test void explicitForeignTeamRefusalRecordsKnownZeroCallsWithoutTouchingTheModel() {
        ChatModel model=mock(ChatModel.class);
        var executor=new DefaultRunExecutor(model,new org.springframework.ai.tool.ToolCallback[0],null,null,null,null,false);
        clearInvocations(model);
        try(var f=new Fixture(executor)) {
            var command=new CreateRunCommand("owner",null,null,"请读取其他账号的队伍12。","sv-1","singles","rules-1","zh-CN",null,null,Duration.ofSeconds(15));
            var run=f.service.createRun(command);var terminal=RunTestAwait.awaitTerminalEvent(f.events,run.runId());
            assertEquals(AgentEventType.RUN_COMPLETED,terminal.type());
            var snapshot=usage(terminal);
            assertEquals(0,snapshot.get("modelCallCount"));assertEquals("COMPLETE",snapshot.get("usageStatus"));
            assertEquals(0L,((Map<?,?>)snapshot.get("totals")).get("totalTokens"));
            assertTrue(((List<?>)snapshot.get("calls")).isEmpty());
            verifyNoInteractions(model);
        }
    }
    @Test void failedRunTerminalKeepsOnlySafeProviderStatusWithoutAddingPostTerminalUsage() {
        var model=mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new org.springframework.ai.retry.TransientAiException("503 - PRIVATE_PROVIDER_BODY"));
        try(var f=new Fixture(context->{preflight(context);return new ObservedChatModel(model,context.modelUsage()).call(new Prompt("PRIVATE_PROMPT")).getResult().getOutput().getText();})) {
            var run=f.create(Duration.ofSeconds(15));var terminal=RunTestAwait.awaitTerminalEvent(f.events,run.runId());
            assertEquals(AgentEventType.RUN_FAILED,terminal.type());
            var failure=(Map<?,?>)((Map<?,?>)((List<?>)usage(terminal).get("calls")).getFirst()).get("failure");
            assertEquals(503,failure.get("httpStatus"));assertEquals("UPSTREAM_SERVER_ERROR",failure.get("category"));
            assertFalse(terminal.payload().toString().contains("PRIVATE"));
            assertEquals(1,usage(terminal).get("modelCallCount"));
            assertEquals("UNKNOWN",usage(terminal).get("usageStatus"));
            assertEquals(Set.of("schemaVersion","scope","category","httpStatus","statusSource","causeChainTruncated"),failure.keySet());
            assertTrue(f.events.findAfter(run.runId(),0).stream().noneMatch(e->e.type()==AgentEventType.USAGE));
        }
    }
    @TempDir Path directory;
    static ChatResponse response(){return new ChatResponse(List.of(new Generation(new AssistantMessage("你好"))),
            ChatResponseMetadata.builder().usage(new DefaultUsage(11,3,14,new OpenAiApi.Usage(3,11,14))).build());}
    static void preflight(AgentRunContext context){new ModelContextBudget(com.vio.vioaiagent.config.ContextBudgetProperties.defaults(),List.of(),context.modelUsage()).prepare(new Prompt("fixture"),null);}
    static void record(AgentRunContext context){preflight(context);context.modelUsage().attach();var call=context.modelUsage().begin("CALL","fixture-model");context.modelUsage().response(call,response());context.modelUsage().finish(call,"RETURNED");}
    @SuppressWarnings("unchecked") static Map<String,Object> usage(AgentEvent event){return (Map<String,Object>)event.payload().get("modelUsage");}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> attempts(ChatMessage message){return (List<Map<String,Object>>)message.metadata().get("modelUsageAttempts");}
    @Test void defaultExecutorCapturesDecisionsAndAnswerAndSqliteKeepsOwnerScopedHistoryAcrossReopen() {
        ChatModel model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenReturn(response());
        var executor=new DefaultRunExecutor(model,new org.springframework.ai.tool.ToolCallback[0],null,null,null,null,false);
        try(var f=new Fixture(executor)) {
            var run=f.create(Duration.ofSeconds(15));var terminal=RunTestAwait.awaitTerminalEvent(f.events,run.runId());
            assertEquals(AgentEventType.RUN_COMPLETED,terminal.type());assertEquals(2,usage(terminal).get("modelCallCount"));
            assertEquals("COMPLETE",usage(terminal).get("usageStatus"));
            assertEquals(1,f.events.findAfter(run.runId(),0).stream().filter(e->e.type()==AgentEventType.USAGE).count());
            var reopened=new SqliteLocalHistoryRepository(f.path,new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
            var answer=reopened.findByConversationForUser(run.conversationId(),"owner").stream().filter(m->m.role().equals("assistant")).findFirst().orElseThrow();
            assertEquals(1,attempts(answer).size());assertEquals(2,attempts(answer).get(0).get("modelCallCount"));
            assertTrue(reopened.findByConversationForUser(run.conversationId(),"other-owner").isEmpty());
            assertThrows(RuntimeException.class,()->f.service.events(run.runId(),"other-owner",0));
        }
    }
    @Test void failureRetainsAttemptCountAndUnknownUsageWithoutCopyingProviderErrorText() {
        try(var f=new Fixture(context->{preflight(context);context.modelUsage().attach();var call=context.modelUsage().begin("CALL","fixture-model");context.modelUsage().finish(call,"FAILED");throw new IllegalStateException("secret provider body");})) {
            var run=f.create(Duration.ofSeconds(15));var terminal=RunTestAwait.awaitTerminalEvent(f.events,run.runId());
            assertEquals(AgentEventType.RUN_FAILED,terminal.type());assertEquals(1,usage(terminal).get("modelCallCount"));
            assertEquals("UNKNOWN",usage(terminal).get("usageStatus"));assertFalse(terminal.payload().toString().contains("secret"));
        }
    }
    @Test void cancellationAndTimeoutFreezeInFlightUsageAndIgnoreLateProviderCompletion() throws Exception {
        for(boolean timeout:List.of(false,true)) {
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),late=new CountDownLatch(1);
            try(var f=new Fixture(context->{
                preflight(context);context.modelUsage().attach();var call=context.modelUsage().begin("CALL","fixture-model");entered.countDown();
                while(release.getCount()>0){try{release.await(50,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){}}
                context.modelUsage().response(call,response());context.modelUsage().finish(call,"RETURNED");late.countDown();return "late";
            })) {
                var run=f.create(timeout?Duration.ofMillis(600):Duration.ofSeconds(15));assertTrue(entered.await(3,TimeUnit.SECONDS));
                if(!timeout)f.service.cancel(run.runId(),"owner");
                var terminal=RunTestAwait.awaitTerminalEvent(f.events,run.runId());
                assertEquals(timeout?AgentEventType.RUN_TIMED_OUT:AgentEventType.RUN_CANCELLED,terminal.type());
                var before=usage(terminal);assertEquals(1,before.get("modelCallCount"));assertEquals("UNKNOWN",before.get("usageStatus"));
                release.countDown();assertTrue(late.await(3,TimeUnit.SECONDS));
                assertEquals(before,usage(f.events.findAfter(run.runId(),0).getLast()));
                assertTrue(f.history.findByConversationForUser(run.conversationId(),"owner").stream().noneMatch(m->m.role().equals("assistant")));
            } finally {release.countDown();}
        }
    }
    @Test void approvalResumeRetainsEarlierModelWorkWithoutCountingConfirmationAsAnotherModelCall() throws Exception {
        AtomicInteger executions=new AtomicInteger();
        try(var f=new Fixture(context->{
            if(executions.incrementAndGet()==1) {
                record(context);var parent=ToolExecutionContextHolder.current();
                parent.eventSink().accept(new ToolGovernanceEvent("approval.required",parent.runId(),"fixture","event",Instant.now(),Map.of("approvalId","fixture")));
                return "等待确认";
            }
            context.modelUsage().noModelCalls();return "已确认";
        })) {
            var run=f.create(Duration.ofSeconds(15));
            long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(f.events.findAfter(run.runId(),0).stream().noneMatch(e->e.type()==AgentEventType.USAGE) && System.nanoTime()<limit)Thread.sleep(10);
            assertEquals(1,f.events.findAfter(run.runId(),0).stream().filter(e->e.type()==AgentEventType.USAGE).count());
            f.service.resolveApproval(run.runId(),"owner",true,"fixture approval");
            assertEquals(AgentEventType.RUN_COMPLETED,RunTestAwait.awaitTerminalEvent(f.events,run.runId()).type());
            var message=f.history.findByConversationForUser(run.conversationId(),"owner").stream().filter(m->m.role().equals("assistant")).findFirst().orElseThrow();
            assertEquals(2,attempts(message).size());assertEquals(1,attempts(message).stream().mapToInt(m->((Number)m.get("modelCallCount")).intValue()).sum());
            var task=(Map<?,?>)attempts(message).getLast().get("taskBudget");
            assertEquals(1,task.get("modelCalls"));assertEquals("ACTIVE",task.get("status"));
        }
    }
    class Fixture implements AutoCloseable {
        final String path=directory.resolve(UUID.randomUUID()+".sqlite").toString();
        final InMemoryRunRepository runs=new InMemoryRunRepository();final InMemoryEventRepository events=new InMemoryEventRepository();
        final SqliteLocalHistoryRepository history=new SqliteLocalHistoryRepository(path,new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        final PokemonAgentRunService service;
        Fixture(RunExecutor executor){service=new PokemonAgentRunService(runs,events,history,history,executor,
                Executors.newSingleThreadExecutor(),Executors.newSingleThreadScheduledExecutor());}
        RunView create(Duration timeout){return service.createRun(new CreateRunCommand("owner",null,null,"你好","sv-1","singles","rules-1","zh-CN",null,null,timeout));}
        public void close(){service.shutdown();}
    }
}
