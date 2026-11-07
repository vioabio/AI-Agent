package com.vio.vioaiagent.run;

import com.vio.vioaiagent.config.*;
import com.vio.vioaiagent.observability.*;
import com.vio.vioaiagent.security.*;
import com.vio.vioaiagent.tools.governance.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PokemonAgentRunServiceTaskBudgetTest {
    private static ChatResponse response(){return new ChatResponse(List.of(new Generation(new AssistantMessage("答复"))));}
    private static ObservedChatModel observed(ChatModel model,ModelUsageLedger ledger) {
        return new ObservedChatModel(model,ledger,new ModelContextBudget(ContextBudgetProperties.defaults(),List.of(),ledger));
    }
    private static CreateRunCommand command(String owner) {
        return new CreateRunCommand(owner,null,null,"你好","sv-1","singles","rules-1","zh-CN",null,null,Duration.ofSeconds(20));
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> task(AgentEvent event) {
        return (Map<String,Object>)((Map<?,?>)event.payload().get("modelUsage")).get("taskBudget");
    }
    @Test void callAndTokenExhaustionBecomeTypedFailuresWithoutCompletedAssistantHistory() {
        for(var limit:List.of(new TaskBudgetProperties(1,131072),new TaskBudgetProperties(16,1))) {
            var model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenReturn(response());
            var executor=new DefaultRunExecutor(model,new org.springframework.ai.tool.ToolCallback[0],null,null,null,null,false);
            try(var f=new Fixture(executor,limit)) {
                var run=f.service.createRun(command("owner"));var terminal=RunTestAwait.awaitTerminalEvent(f.events,run.runId());
                assertEquals(AgentEventType.RUN_FAILED,terminal.type());assertEquals("RUN_TASK_BUDGET_EXCEEDED",terminal.payload().get("errorType"));
                assertEquals(limit.maxModelCalls()==1?1:0,task(terminal).get("modelCalls"));
                assertEquals(limit.maxModelCalls()==1?"CALL_LIMIT":"TOKEN_LIMIT",task(terminal).get("blockReason"));
                verify(model,times(limit.maxModelCalls()==1?1:0)).call(any(Prompt.class));
                assertTrue(f.messages.findByConversationForUser(run.conversationId(),"owner").stream().noneMatch(m->m.role().equals("assistant")));
            }
        }
    }
    @Test void differentRunsAndOwnersHaveIndependentBudgetsAndCompletedHistoryCarriesCumulativeSnapshot() {
        var model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenReturn(response());
        var executor=new DefaultRunExecutor(model,new org.springframework.ai.tool.ToolCallback[0],null,null,null,null,false);
        try(var f=new Fixture(executor,new TaskBudgetProperties(2,131072))) {
            Set<Object> ids=new HashSet<>();
            for(String owner:List.of("owner","other-owner","owner")) {
                var run=f.service.createRun(command(owner));var terminal=RunTestAwait.awaitTerminalEvent(f.events,run.runId());
                assertEquals(AgentEventType.RUN_COMPLETED,terminal.type());assertEquals(2,task(terminal).get("modelCalls"));
                assertTrue(ids.add(task(terminal).get("budgetId")));
                var answer=f.messages.findByConversationForUser(run.conversationId(),owner).getLast();
                assertEquals("assistant",answer.role());assertTrue(answer.metadata().containsKey("modelUsageAttempts"));
                assertThrows(RuntimeException.class,()->f.service.events(run.runId(),"unrelated",0));
            }
            verify(model,times(6)).call(any(Prompt.class));
        }
    }
    @Test void approvalResumeDoesNotResetBudgetAndDeniedCallIsZeroInNewAttempt() throws Exception {
        var model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenReturn(response());var executions=new AtomicInteger();
        RunExecutor executor=context->{
            observed(model,context.modelUsage()).call(new Prompt("fixture"));
            if(executions.incrementAndGet()==1) {
                var parent=ToolExecutionContextHolder.current();
                parent.eventSink().accept(new ToolGovernanceEvent("approval.required",parent.runId(),"fixture","event",Instant.now(),Map.of("approvalId","fixture")));
            }
            return "等待确认";
        };
        try(var f=new Fixture(executor,new TaskBudgetProperties(1,131072))) {
            var run=f.service.createRun(command("owner"));awaitUsage(f.events,run.runId());
            f.service.resolveApproval(run.runId(),"owner",true,"fixture approval");
            var terminal=RunTestAwait.awaitTerminalEvent(f.events,run.runId());
            assertEquals("RUN_TASK_BUDGET_EXCEEDED",terminal.payload().get("errorType"));assertEquals(1,task(terminal).get("modelCalls"));
            assertEquals(0,((Map<?,?>)terminal.payload().get("modelUsage")).get("modelCallCount"));
            verify(model,times(1)).call(any(Prompt.class));assertEquals(1,executions.get());
        }
    }
    @Test void recoveredApprovalRestoresOnlyFreshFrozenCreditAndNeverResetsMissingOrStaleState() {
        for(String state:List.of("fresh","missing","stale")) {
            var runs=new InMemoryRunRepository();var events=new InMemoryEventRepository();
            var conversations=new InMemoryConversationRepository();var messages=new InMemoryChatMessageRepository();
            var model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenReturn(response());
            var oldTask=new TaskModelBudget(new TaskBudgetProperties(1,131072));var oldLedger=new ModelUsageLedger(oldTask);
            observed(model,oldLedger).call(new Prompt("prior work"));var snapshot=oldLedger.freeze();clearInvocations(model);
            Instant now=Instant.now();var run=AgentRun.create("run-recover-"+state,"session","conversation","owner","fixture","sv-1","singles","rules-1","zh-CN","trace",null,now.plusSeconds(120));
            runs.save(run);run.transitionTo(AgentRunStatus.RUNNING);runs.save(run);run.transitionTo(AgentRunStatus.WAITING_APPROVAL);runs.save(run);
            conversations.save(new Conversation("conversation","owner","fixture",now,now));
            var approvals=new InMemoryHitlApprovalRepository();var hitl=new HitlManager(HitlAuditSink.noop());hitl.setApprovalRepository(approvals);
            String approval=UUID.randomUUID().toString();
            approvals.createOrGet(new HitlApproval(approval,"owner",run.id(),"fixture","1","digest","MEDIUM",now.plusSeconds(90),HitlApproval.Decision.PENDING,null,null,null,null,null,0,now,now),now);
            long id=1;events.appendNext(AgentEvent.of(id++,AgentEventType.RUN_CREATED,"session",run.id(),"trace",Map.of()));
            if(state.equals("stale"))events.appendNext(AgentEvent.of(id++,AgentEventType.USAGE,"session",run.id(),"trace",snapshot));
            events.appendNext(AgentEvent.of(id++,AgentEventType.APPROVAL_REQUIRED,"session",run.id(),"trace",Map.of("approvalId",approval)));
            if(state.equals("fresh"))events.appendNext(AgentEvent.of(id,AgentEventType.USAGE,"session",run.id(),"trace",snapshot));
            RunExecutor executor=context->{observed(model,context.modelUsage()).call(new Prompt("resumed work"));return "should not complete";};
            var properties=new DurableRunProperties();properties.setDurableResume(true);
            var service=new PokemonAgentRunService(runs,events,conversations,messages,executor,Executors.newSingleThreadExecutor(),Executors.newSingleThreadScheduledExecutor(),hitl,new InMemoryDurableRunStateRepository(),properties);
            try {
                service.recoverInFlightRuns();assertEquals(AgentRunStatus.WAITING_APPROVAL,runs.findById(run.id()).orElseThrow().status());
                service.resolveApproval(run.id(),"owner",true,"fixture");var terminal=RunTestAwait.awaitTerminalEvent(events,run.id());
                assertEquals(state.equals("fresh")?"RUN_TASK_BUDGET_EXCEEDED":"RUN_TASK_BUDGET_UNAVAILABLE",terminal.payload().get("errorType"));
                if(state.equals("fresh"))assertEquals(1,task(terminal).get("modelCalls"));
                else {assertEquals(false,task(terminal).get("stateKnown"));assertNull(task(terminal).get("chargedTokens"));}
                verify(model,never()).call(any(Prompt.class));
            } finally {service.shutdown();hitl.shutdown();}
        }
    }
    private static void awaitUsage(InMemoryEventRepository events,String run) throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(events.findAfter(run,0).stream().noneMatch(e->e.type()==AgentEventType.USAGE) && System.nanoTime()<deadline)Thread.sleep(10);
        assertTrue(events.findAfter(run,0).stream().anyMatch(e->e.type()==AgentEventType.USAGE));
    }
    private static final class Fixture implements AutoCloseable {
        final InMemoryEventRepository events=new InMemoryEventRepository();
        final InMemoryChatMessageRepository messages=new InMemoryChatMessageRepository();
        final PokemonAgentRunService service;
        Fixture(RunExecutor executor,TaskBudgetProperties limits) {
            service=new PokemonAgentRunService(new InMemoryRunRepository(),events,new InMemoryConversationRepository(),messages,executor,Executors.newSingleThreadExecutor(),Executors.newSingleThreadScheduledExecutor());
            service.setTaskBudgetProperties(limits);
        }
        @Override public void close(){service.shutdown();}
    }
}
