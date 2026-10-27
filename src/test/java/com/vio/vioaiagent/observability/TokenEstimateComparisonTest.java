package com.vio.vioaiagent.observability;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import com.vio.vioaiagent.config.ContextBudgetProperties;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TokenEstimateComparisonTest {
    @Test void rawAndBudgetCountsPreserveFixedFramingAndPerTextRounding() {
        var estimator=new ContextTokenEstimator(125);
        assertEquals(new ContextTokenEstimator.Estimate(17,18),estimator.estimateMessage(new UserMessage("hello")));
        assertEquals(new ContextTokenEstimator.Estimate(18,19),estimator.estimateMessage(new UserMessage("你好")));
        var call=new AssistantMessage("",Map.of(),List.of(new AssistantMessage.ToolCall("id","function","calc","{\"x\":1}")));
        assertEquals(new ContextTokenEstimator.Estimate(39,43),estimator.estimateMessage(call));
        var result=new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse("i","calc","hello")));
        assertEquals(new ContextTokenEstimator.Estimate(35,38),estimator.estimateMessage(result));
        assertThrows(IllegalArgumentException.class,()->new ContextTokenEstimator(99));
    }
    private Map<String,Object> context() {
        var ledger=new ModelUsageLedger();var budget=new ModelContextBudget(ContextBudgetProperties.defaults(),List.of(),ledger);
        budget.prepare(new Prompt("hello"),null);
        @SuppressWarnings("unchecked") var value=(Map<String,Object>)ledger.snapshot().get("contextBudget");return value;
    }
    @Test void inputPairShowsSignedUnderestimateWithoutUsingOutputOrDividingTheSafetyMargin() {
        var compared=TokenEstimateComparison.compare(context(),new ProviderTokenUsage(20,200,220,"FIXTURE"));
        assertEquals("PAIR_RECORDED",compared.get("status"));assertEquals(17L,compared.get("baseInputTokens"));
        assertEquals(18L,compared.get("budgetInputTokens"));assertEquals(-2L,compared.get("budgetErrorTokens"));assertEquals(false,compared.get("budgetCovered"));
        assertEquals("USAGE_UNAVAILABLE",TokenEstimateComparison.compare(context(),null).get("reason"));
        assertEquals("NONPOSITIVE_INPUT",TokenEstimateComparison.compare(context(),new ProviderTokenUsage(0,1,1,"FIXTURE")).get("reason"));
        assertEquals("ESTIMATE_NOT_RECORDED",TokenEstimateComparison.compare(Map.of(),null).get("reason"));
        var invalid=new HashMap<>(context());invalid.put("baseEstimate",Map.of("inputTokens",1));
        assertEquals("INVALID_ESTIMATE",TokenEstimateComparison.compare(invalid,new ProviderTokenUsage(1,1,2,"FIXTURE")).get("reason"));
    }
    @Test void callAndStreamSnapshotsCaptureTheSamePreparedEstimateAndFreezeIt() {
        var model=mock(ChatModel.class);var ledger=new ModelUsageLedger();
        var budget=new ModelContextBudget(ContextBudgetProperties.defaults(),List.of(),ledger);
        var response=ModelUsageLedgerTest.response(10,3,13);
        when(model.call(any(Prompt.class))).thenReturn(response);when(model.stream(any(Prompt.class))).thenReturn(reactor.core.publisher.Flux.just(response));
        var observed=new ObservedChatModel(model,ledger,budget);observed.call(new Prompt("hello"));observed.stream(new Prompt("hello")).collectList().block();
        var snapshot=ledger.freeze();
        for(Object row:(List<?>)snapshot.get("calls")) {
            var comparison=(Map<?,?>)((Map<?,?>)row).get("tokenEstimateComparison");assertEquals("PAIR_RECORDED",comparison.get("status"));
            assertEquals(10L,comparison.get("providerInputTokens"));assertEquals(17L,comparison.get("baseInputTokens"));
        }
        assertSame(snapshot,ledger.snapshot());
    }
}
