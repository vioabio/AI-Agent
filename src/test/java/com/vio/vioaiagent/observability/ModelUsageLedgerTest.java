package com.vio.vioaiagent.observability;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.openai.api.OpenAiApi;
import reactor.core.publisher.Flux;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelUsageLedgerTest {
    static ChatResponse response(Integer input,Integer output,Integer total) {
        var nativeUsage=new OpenAiApi.Usage(output,input,total);
        return new ChatResponse(List.of(new Generation(new AssistantMessage("private content must not be recorded"))),
                ChatResponseMetadata.builder().model("response-model").usage(new DefaultUsage(input,output,total,nativeUsage)).build());
    }
    static ModelUsageLedger ledger(){var ledger=new ModelUsageLedger();ledger.attach();return ledger;}
    @SuppressWarnings("unchecked") static Map<String,Object> tokens(Map<String,Object> snapshot){return (Map<String,Object>)snapshot.get("totals");}
    @Test void missingSdkCountsDoNotBecomeZeroAndBothProviderTypesAreReadFromNativeFields() {
        assertNull(ProviderTokenUsage.read(new DefaultUsage(null,null)));
        assertNull(ProviderTokenUsage.read(new DefaultUsage(0,0,0,new OpenAiApi.Usage(null,null,null))));
        assertNull(ProviderTokenUsage.read(new EmptyUsage()));
        var raw=new com.alibaba.cloud.ai.dashscope.api.DashScopeApi.TokenUsage(4,8,12);
        assertEquals(12,ProviderTokenUsage.read(new DefaultUsage(8,4,12,raw)).totalTokens());
        assertEquals(0,ProviderTokenUsage.read(response(0,0,0).getMetadata().getUsage()).totalTokens());
    }
    @Test void inconsistentNegativeOrPartlyMissingNativeUsageRemainsUnknown() {
        for(var response:List.of(response(3,4,99),response(-1,4,3),response(3,null,3)))assertNull(ProviderTokenUsage.read(response.getMetadata().getUsage()));
    }
    @Test void decisionsAndFinalCallsAreCountedOnceAndOnlyNumericMetadataIsRetained() throws Exception {
        var ledger=ledger();var a=ledger.begin("CALL","configured-model");ledger.response(a,response(10,2,12));ledger.finish(a,"RETURNED");
        var b=ledger.begin("CALL","configured-model");ledger.response(b,response(15,3,18));ledger.finish(b,"RETURNED");
        var snapshot=ledger.freeze();assertEquals(2,snapshot.get("modelCallCount"));assertEquals(30L,tokens(snapshot).get("totalTokens"));
        String json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(snapshot);
        assertFalse(json.contains("private content"));assertFalse(json.contains("nativeUsage"));assertEquals("COMPLETE",snapshot.get("usageStatus"));
    }
    @Test void streamingUsageUsesTheLastSnapshotEvenForUsageOnlyTailAndRepeatedSnapshots() {
        var ledger=ledger();var call=ledger.begin("STREAM","model");
        ledger.response(call,response(10,1,11));ledger.response(call,response(10,3,13));
        var tail=new ChatResponse(List.of(),response(10,3,13).getMetadata());ledger.response(call,tail);ledger.finish(call,"RETURNED");
        assertEquals(13L,tokens(ledger.freeze()).get("totalTokens"));
    }
    @Test void malformedOrRegressingFinalSnapshotsDoNotPromoteEarlierPartialUsage() {
        for(boolean malformed:List.of(true,false)) {
            var ledger=ledger();var call=ledger.begin("STREAM","model");ledger.response(call,response(10,3,13));
            ledger.response(call,malformed?response(10,5,100):response(10,1,11));ledger.finish(call,"RETURNED");
            assertEquals("UNKNOWN",ledger.freeze().get("usageStatus"));assertNull(tokens(ledger.snapshot()).get("totalTokens"));
        }
    }
    @Test void missingOrFailedCallsCannotDisappearFromTheDenominator() {
        var ledger=ledger();var first=ledger.begin("CALL","m");ledger.response(first,response(10,2,12));ledger.finish(first,"RETURNED");
        var failed=ledger.begin("CALL","m");ledger.finish(failed,"FAILED");
        var snapshot=ledger.freeze();assertEquals(2,snapshot.get("modelCallCount"));assertEquals(1,snapshot.get("callsWithUsage"));
        assertEquals("PARTIAL",snapshot.get("usageStatus"));assertNull(tokens(snapshot).get("totalTokens"));
    }
    @Test void cancellationFreezesTheInFlightAttemptAndRejectsLateResponsesOrNewCalls() {
        var ledger=ledger();var call=ledger.begin("STREAM","m");var frozen=ledger.freeze();
        ledger.response(call,response(2,3,5));ledger.finish(call,"RETURNED");
        assertSame(frozen,ledger.snapshot());assertNull(tokens(frozen).get("totalTokens"));
        assertThrows(java.util.concurrent.CancellationException.class,()->ledger.begin("CALL","m"));
    }
    @Test void absentInstrumentationAndExplicitNoModelPathsAreDistinct() {
        var unknown=new ModelUsageLedger();assertEquals("NOT_INSTRUMENTED",unknown.freeze().get("coverage"));
        var known=new ModelUsageLedger();known.noModelCalls();assertEquals("COMPLETE",known.freeze().get("usageStatus"));
        assertEquals(0L,tokens(known.snapshot()).get("totalTokens"));
    }
    @Test void modelDecoratorIsLazyForStreamingAndDoesNotCountOptionsOrOutputChunksAsCalls() {
        var ledger=ledger();ChatModel model=mock(ChatModel.class);when(model.getDefaultOptions()).thenReturn(ChatOptions.builder().model("model").build());
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response(10,1,11),response(10,3,13)));
        var observed=new ObservedChatModel(model,ledger);observed.getDefaultOptions();
        var stream=observed.stream(new Prompt("not persisted"));assertEquals(0,ledger.snapshot().get("modelCallCount"));
        assertEquals(2,stream.collectList().block().size());assertEquals(1,ledger.snapshot().get("modelCallCount"));
        assertEquals(13L,tokens(ledger.freeze()).get("totalTokens"));
    }
    @Test void decoratorRethrowsFailuresAndKeepsAttemptsIsolated() {
        ChatModel model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("secret failure body"));
        var first=ledger();assertThrows(IllegalStateException.class,()->new ObservedChatModel(model,first).call(new Prompt("secret input")));
        var second=ledger();assertEquals(1,first.freeze().get("modelCallCount"));assertEquals(0,second.freeze().get("modelCallCount"));
        assertFalse(first.snapshot().toString().contains("secret"));
    }
    @Test void malformedOptionalMetadataDoesNotFailTheAnswerOrLeakItsBody() {
        Usage usage=mock(Usage.class);when(usage.getNativeUsage()).thenThrow(new IllegalStateException("private metadata"));
        var response=new ChatResponse(List.of(new Generation(new AssistantMessage("valid answer"))),ChatResponseMetadata.builder().usage(usage).build());
        ChatModel model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenReturn(response);
        var ledger=ledger();assertSame(response,new ObservedChatModel(model,ledger).call(new Prompt("private")));
        assertEquals("UNKNOWN",ledger.freeze().get("usageStatus"));assertFalse(ledger.snapshot().toString().contains("private"));
    }
    @Test void boundedRecordsKeepOverflowCallsInTheDenominator() {
        var ledger=ledger();for(int i=0;i<65;i++){var call=ledger.begin("CALL","m");ledger.response(call,response(1,1,2));ledger.finish(call,"RETURNED");}
        var snapshot=ledger.freeze();assertEquals(65,snapshot.get("modelCallCount"));assertEquals(64,((List<?>)snapshot.get("calls")).size());
        assertEquals(true,snapshot.get("recordsTruncated"));assertNull(tokens(snapshot).get("totalTokens"));
    }
    @SuppressWarnings("unchecked") static Map<String,Object> firstFailure(ModelUsageLedger ledger) {
        return (Map<String,Object>)((Map<?,?>)((List<?>)ledger.snapshot().get("calls")).getFirst()).get("failure");
    }
    @Test void callAndBothStreamErrorPathsKeepOriginalErrorsWithSafeDiagnostics() {
        for(String mode:List.of("CALL","STREAM_THROW","STREAM_ERROR")) {
            var model=mock(ChatModel.class);var error=new org.springframework.ai.retry.TransientAiException("503 - PRIVATE_RESPONSE_BODY");
            if(mode.equals("CALL"))when(model.call(any(Prompt.class))).thenThrow(error);
            else if(mode.equals("STREAM_THROW"))when(model.stream(any(Prompt.class))).thenThrow(error);
            else when(model.stream(any(Prompt.class))).thenReturn(Flux.error(error));
            var ledger=ledger();var observed=new ObservedChatModel(model,ledger);
            RuntimeException thrown=assertThrows(RuntimeException.class,()->{
                if(mode.equals("CALL"))observed.call(new Prompt("PRIVATE_PROMPT"));
                else observed.stream(new Prompt("PRIVATE_PROMPT")).collectList().block();
            });
            assertSame(error,thrown);assertEquals(503,firstFailure(ledger).get("httpStatus"));
            assertEquals("UPSTREAM_SERVER_ERROR",firstFailure(ledger).get("category"));
            assertFalse(ledger.freeze().toString().contains("PRIVATE"));assertEquals(1,ledger.snapshot().get("modelCallCount"));
            assertNull(tokens(ledger.snapshot()).get("totalTokens"));
        }
    }
    @Test void streamCancellationAndLateFailureCannotOverwriteAFrozenOrFinishedAttempt() {
        var model=mock(ChatModel.class);when(model.stream(any(Prompt.class))).thenReturn(Flux.never());
        var ledger=ledger();var subscription=new ObservedChatModel(model,ledger).stream(new Prompt("PRIVATE")).subscribe();
        subscription.dispose();assertEquals("CANCELLED",firstFailure(ledger).get("category"));
        var closed=ledger.freeze();assertSame(closed,ledger.snapshot());
        var separate=ledger();var call=separate.begin("CALL","m");separate.finish(call,"RETURNED");
        separate.fail(call,new org.springframework.ai.retry.TransientAiException("500 - PRIVATE"));
        assertFalse(((Map<?,?>)((List<?>)separate.freeze().get("calls")).getFirst()).containsKey("failure"));
        var inFlight=ledger();var pending=inFlight.begin("CALL","m");var snapshot=inFlight.freeze();
        inFlight.fail(pending,new IllegalStateException("PRIVATE"));assertSame(snapshot,inFlight.snapshot());
        assertFalse(snapshot.toString().contains("failure="));
    }
}
