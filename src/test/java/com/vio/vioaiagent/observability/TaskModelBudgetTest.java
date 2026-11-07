package com.vio.vioaiagent.observability;

import com.vio.vioaiagent.config.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.api.OpenAiApi;
import reactor.core.publisher.Flux;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskModelBudgetTest {
    private static final ContextBudgetProperties CONTEXT = new ContextBudgetProperties(1024,128,256,2,125);
    private static ObservedChatModel observed(ChatModel model,ModelUsageLedger ledger) {
        return new ObservedChatModel(model,ledger,new ModelContextBudget(CONTEXT,List.of(),ledger));
    }
    private static ChatResponse response(Integer total) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("答复"))),
                ChatResponseMetadata.builder().usage(total==null?null:
                        new DefaultUsage(total-1,1,total,new OpenAiApi.Usage(1,total-1,total))).build());
    }
    @Test void exhaustedCallsBlockBeforeProviderAndDoNotIncrementAttemptOrTaskCounters() {
        var task=new TaskModelBudget(new TaskBudgetProperties(1,10000));
        var model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenReturn(response(10));
        var ledger=new ModelUsageLedger(task);var observed=observed(model,ledger);
        observed.call(new Prompt("hi"));
        var failure=assertThrows(TaskBudgetExceededException.class,()->observed.call(new Prompt("again")));
        assertEquals(TaskBudgetExceededException.Reason.CALL_LIMIT,failure.reason());
        verify(model,times(1)).call(any(Prompt.class));assertEquals(1,ledger.freeze().get("modelCallCount"));
        assertEquals(1,task.snapshot().get("modelCalls"));assertEquals("BLOCKED",task.snapshot().get("status"));
    }
    @Test void insufficientTokensAndMissingEstimateAreNeverAdmittedOrReportedAsProviderUsage() {
        var model=mock(ChatModel.class);var task=new TaskModelBudget(new TaskBudgetProperties(16,1));
        var ledger=new ModelUsageLedger(task);
        assertThrows(TaskBudgetExceededException.class,()->observed(model,ledger).call(new Prompt("hi")));
        verify(model,never()).call(any(Prompt.class));verify(model,never()).stream(any(Prompt.class));assertEquals(0,task.snapshot().get("modelCalls"));
        assertEquals(0,ledger.freeze().get("modelCallCount"));assertEquals("COMPLETE",ledger.snapshot().get("usageStatus"));
        var missing=new ModelUsageLedger(new TaskModelBudget(TaskBudgetProperties.defaults()));
        assertEquals(TaskBudgetExceededException.Reason.ESTIMATE_UNAVAILABLE,
                assertThrows(TaskBudgetExceededException.class,()->missing.begin("CALL","fixture")).reason());
    }
    @Test void unknownUsageAndFailuresKeepReservationsAcrossAttemptsWithoutResetOrRefund() {
        var task=new TaskModelBudget(new TaskBudgetProperties(2,10000));
        var model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenReturn(response(null))
                .thenThrow(new IllegalStateException("private provider error"));
        var first=new ModelUsageLedger(task);observed(model,first).call(new Prompt("hi"));
        var frozen=first.freeze();long amount=((Number)task.snapshot().get("chargedTokens")).longValue();
        assertTrue(amount>0);assertEquals("UNKNOWN",frozen.get("usageStatus"));
        var second=new ModelUsageLedger(task);assertThrows(IllegalStateException.class,()->observed(model,second).call(new Prompt("hi")));
        assertEquals(amount*2,task.snapshot().get("chargedTokens"));second.freeze();
        var third=new ModelUsageLedger(task);assertThrows(TaskBudgetExceededException.class,()->observed(model,third).call(new Prompt("hi")));
        assertEquals(0,third.freeze().get("modelCallCount"));assertEquals(1,frozen.get("modelCallCount"));
        assertFalse(frozen.toString().contains("private"));verify(model,times(2)).call(any(Prompt.class));
    }
    @Test void nativeOverrunIsChargedAndStopsFurtherWorkWithoutReplacingNativeUsage() {
        var task=new TaskModelBudget(new TaskBudgetProperties(16,1000));
        var model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenReturn(response(2000));
        var ledger=new ModelUsageLedger(task);
        assertThrows(TaskBudgetExceededException.class,()->observed(model,ledger).call(new Prompt("hi")));
        assertEquals(2000L,task.snapshot().get("chargedTokens"));assertEquals(0L,task.snapshot().get("remainingTokens"));
        assertEquals(2000L,((Map<?,?>)ledger.snapshot().get("totals")).get("totalTokens"));
        assertEquals("TOKEN_LIMIT",task.snapshot().get("blockReason"));
    }
    @Test void streamingAdmissionIsLazyAndCumulativeUsageIsNotAddedForEveryChunk() {
        var task=new TaskModelBudget(new TaskBudgetProperties(1,5000));
        var model=mock(ChatModel.class);when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response(400),response(600),response(600)));
        var ledger=new ModelUsageLedger(task);var flux=observed(model,ledger).stream(new Prompt("hi"));
        assertEquals(0,task.snapshot().get("modelCalls"));flux.collectList().block();
        assertEquals(600L,task.snapshot().get("chargedTokens"));assertEquals(600L,((Map<?,?>)ledger.freeze().get("totals")).get("totalTokens"));
        assertThrows(TaskBudgetExceededException.class,()->observed(model,new ModelUsageLedger(task)).stream(new Prompt("again")).blockLast());
        verify(model,times(1)).stream(any(Prompt.class));
    }
    @Test void atomicAdmissionCannotOverspendUnderConcurrentRequests() throws Exception {
        var task=new TaskModelBudget(new TaskBudgetProperties(4,400));
        try(var pool=Executors.newFixedThreadPool(8)) {
            var futures=new ArrayList<Future<Boolean>>();
            for(int i=0;i<24;i++)futures.add(pool.submit(()->{
                try {task.reserve(75,25);return true;}catch(TaskBudgetExceededException exhausted){return false;}
            }));
            int admitted=0;for(var future:futures)if(future.get(3,TimeUnit.SECONDS))admitted++;
            assertEquals(4,admitted);assertEquals(400L,task.snapshot().get("chargedTokens"));
        }
    }
    @Test void decreasingUsageRemainsUnknownButCannotHideALaterLargerTokenSample() {
        var task=new TaskModelBudget(new TaskBudgetProperties(16,1000));
        var model=mock(ChatModel.class);when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response(600),response(400),response(1500)));
        var ledger=new ModelUsageLedger(task);
        assertThrows(TaskBudgetExceededException.class,()->observed(model,ledger).stream(new Prompt("hi")).blockLast());
        assertEquals(1500L,task.snapshot().get("chargedTokens"));assertEquals("BLOCKED",task.snapshot().get("status"));
        assertEquals("UNKNOWN",ledger.freeze().get("usageStatus"));
    }
    @Test void restoreNeverRaisesOriginalLimitsAndUnknownStateDoesNotPreventNoModelConfirmation() {
        var task=new TaskModelBudget(new TaskBudgetProperties(2,1000));task.reserve(100,100);
        var restored=TaskModelBudget.restore(task.snapshot(),new TaskBudgetProperties(16,10000));
        assertEquals(2,restored.snapshot().get("maxModelCalls"));assertEquals(1000L,restored.snapshot().get("tokenLimit"));
        restored.reserve(100,100);assertThrows(TaskBudgetExceededException.class,()->restored.reserve(1,1));
        var corrupt=new LinkedHashMap<>(task.snapshot());corrupt.put("remainingTokens",999L);
        assertEquals("STATE_UNAVAILABLE",TaskModelBudget.restore(corrupt,TaskBudgetProperties.defaults()).snapshot().get("blockReason"));
        var unknown=TaskModelBudget.unavailable(TaskBudgetProperties.defaults());
        var confirmation=new ModelUsageLedger(unknown);confirmation.noModelCalls();
        assertEquals(0,confirmation.freeze().get("modelCallCount"));
        assertThrows(TaskBudgetExceededException.class,()->unknown.reserve(1,1));
        task.close();assertThrows(CancellationException.class,()->task.reserve(1,1));
    }
    @Test void frozenAttemptRejectsLateChargesAndClosedRunDoesNotAdmitAnotherRequest() {
        var task=new TaskModelBudget(TaskBudgetProperties.defaults());var ledger=new ModelUsageLedger(task);
        new ModelContextBudget(CONTEXT,List.of(),ledger).prepare(new Prompt("hi"),null);
        var call=ledger.begin("CALL","fixture");var frozen=ledger.freeze();
        long charged=((Number)task.snapshot().get("chargedTokens")).longValue();
        ledger.response(call,response(9999));assertEquals(charged,task.snapshot().get("chargedTokens"));
        assertEquals(frozen,ledger.snapshot());task.close();
        assertThrows(CancellationException.class,()->observed(mock(ChatModel.class),new ModelUsageLedger(task)).call(new Prompt("hi")));
    }
}
