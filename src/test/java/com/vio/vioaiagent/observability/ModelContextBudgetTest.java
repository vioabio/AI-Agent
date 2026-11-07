package com.vio.vioaiagent.observability;

import com.vio.vioaiagent.config.ContextBudgetProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelContextBudgetTest {
    @SuppressWarnings("unchecked") static Map<String,Object> summary(ModelUsageLedger ledger){return (Map<String,Object>)ledger.snapshot().get("contextBudget");}
    static ModelContextBudget budget(ContextBudgetProperties limits,List<Message> history,ModelUsageLedger ledger){return new ModelContextBudget(limits,history,ledger);}
    static ContextBudgetProperties limits(){return new ContextBudgetProperties(1024,128,120,2,125);}
    static ChatResponse response(String finish) {return new ChatResponse(List.of(new Generation(new AssistantMessage("答复"),
            ChatGenerationMetadata.builder().finishReason(finish).build())));}
    @Test void localProxyCountsUnicodeAndSpecialTokenLookingTextAsOrdinaryData() {
        var estimator=new ContextTokenEstimator(125);
        assertTrue(estimator.text("hello")>0);assertTrue(estimator.text("杰尼龟🦊<|endoftext|>")>0);
        assertTrue(estimator.text("杰尼龟")>=new ContextTokenEstimator(100).text("杰尼龟"));
    }
    @Test void historyIsDroppedInPairsAndCurrentVersionToolCallsAndResultsStayIntact() {
        List<Message> history=List.of(new UserMessage("旧问题"),new AssistantMessage("旧答复"),new UserMessage("限制：不使用传说"),new AssistantMessage("确认限制"));
        var ledger=new ModelUsageLedger();var budget=budget(limits(),history,ledger);
        var call=new AssistantMessage("",Map.of(),List.of(new AssistantMessage.ToolCall("call-1","function","lookup","{\"pokemon\":\"squirtle\"}")));
        var tool=new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse("call-1","lookup","真实资料")));
        List<Message> messages=new ArrayList<>();messages.add(new SystemMessage("当前版本：朱紫，休闲单打"));messages.addAll(history);
        messages.add(new UserMessage("当前问题，明确保留限制"));messages.add(call);messages.add(tool);
        var prepared=budget.prepare(new Prompt(messages),null);
        assertTrue(prepared.getContents().contains("当前问题"));assertTrue(prepared.getContents().contains("朱紫"));
        assertTrue(prepared.getInstructions().contains(call));assertTrue(prepared.getInstructions().contains(tool));
        assertEquals(128,prepared.getOptions().getMaxTokens());assertEquals("PROXY_UNCALIBRATED",summary(ledger).get("calibration"));
        assertTrue(((Number)((Map<?,?>)summary(ledger).get("lastEstimate")).get("inputTokens")).intValue()<=896);
        assertFalse(summary(ledger).toString().contains("squirtle"));
    }
    @Test void largeMandatoryInputStopsBeforeProviderAndNeverBecomesAZeroUsageEstimate() {
        var model=mock(ChatModel.class);var ledger=new ModelUsageLedger();
        var observed=new ObservedChatModel(model,ledger,budget(limits(),List.of(),ledger));
        assertThrows(ContextBudgetExceededException.class,()->observed.call(new Prompt("一二三四五六七八九十".repeat(600))));
        verify(model,never()).call(any(Prompt.class));assertEquals(0,ledger.freeze().get("modelCallCount"));
        assertEquals("BLOCKED",summary(ledger).get("status"));assertEquals("COMPLETE",ledger.snapshot().get("usageStatus"));
    }
    @Test void streamingBudgetCheckIsLazyAndBlocksBeforeSubscriptionToProvider() {
        var model=mock(ChatModel.class);var ledger=new ModelUsageLedger();
        var observed=new ObservedChatModel(model,ledger,budget(limits(),List.of(),ledger));
        var stream=observed.stream(new Prompt("大".repeat(5000)));assertNull(summary(ledger));
        assertThrows(ContextBudgetExceededException.class,()->stream.collectList().block());verify(model,never()).stream(any(Prompt.class));
    }
    @Test void toolDefinitionsAndToolResultsAreBudgetedAndCannotBeSilentlyTruncated() {
        ToolCallback callback=mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder().name("lookup").description("词".repeat(2000)).inputSchema("{}").build());
        var options=ToolCallingChatOptions.builder().toolCallbacks(callback).build();
        var ledger=new ModelUsageLedger();
        assertThrows(ContextBudgetExceededException.class,()->budget(limits(),List.of(),ledger).prepare(new Prompt("hi",options),null));
        var result=new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse("1","lookup","文".repeat(2000))));
        assertThrows(ContextBudgetExceededException.class,()->budget(limits(),List.of(),new ModelUsageLedger()).prepare(new Prompt(List.of(new UserMessage("hi"),result)),null));
        assertEquals("文".repeat(2000),result.getResponses().getFirst().responseData());
    }
    @Test void outputLimitOverridesOnlyTheCopyAndKeepsToolCallbacksAndSampling() {
        var callback=mock(ToolCallback.class);when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder().name("read").description("read").inputSchema("{}").build());
        var options=ToolCallingChatOptions.builder().maxTokens(1000).temperature(0.2).toolCallbacks(callback).internalToolExecutionEnabled(false).build();
        var prepared=budget(limits(),List.of(),new ModelUsageLedger()).prepare(new Prompt("hi",options),null);
        assertEquals(128,prepared.getOptions().getMaxTokens());assertEquals(1000,options.getMaxTokens());
        assertEquals(0.2,prepared.getOptions().getTemperature());assertEquals(List.of(callback),((ToolCallingChatOptions)prepared.getOptions()).getToolCallbacks());
        assertEquals(64,budget(limits(),List.of(),new ModelUsageLedger()).prepare(new Prompt("hi",ChatOptions.builder().maxTokens(64).build()),null).getOptions().getMaxTokens());
    }
    @Test void unmatchedHistoryAndUnresolvedNamedToolsFailClosed() {
        var ledger=new ModelUsageLedger();
        assertThrows(ContextBudgetExceededException.class,()->budget(limits(),List.of(new UserMessage("old"),new AssistantMessage("old answer")),ledger).prepare(new Prompt("current"),null));
        var options=ToolCallingChatOptions.builder().toolNames("unknown-schema").build();
        assertThrows(ContextBudgetExceededException.class,()->budget(limits(),List.of(),new ModelUsageLedger()).prepare(new Prompt("hi",options),null));
    }
    @Test void outputLengthFinishFailsTheRunButCountsTheReturnedProviderCall() {
        var model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenReturn(response("length"));
        var ledger=new ModelUsageLedger();var observed=new ObservedChatModel(model,ledger,budget(limits(),List.of(),ledger));
        assertThrows(ContextBudgetExceededException.class,()->observed.call(new Prompt("hi")));
        assertEquals(1,ledger.snapshot().get("modelCallCount"));assertEquals("OUTPUT_LIMIT",summary(ledger).get("blockReason"));
        assertEquals("RETURNED",((Map<?,?>)((List<?>)ledger.snapshot().get("calls")).getFirst()).get("outcome"));
    }
    @Test void historyOmissionNoticeIsCountedAndSessionDataCannotCrossAttempts() {
        var a=new ModelUsageLedger();a.historySource(Map.of("availableTurns",3,"omittedTurns",3));
        var prepared=budget(limits(),List.of(),a).prepare(new Prompt("当前问题"),null);
        assertTrue(prepared.getContents().contains("不要猜测遗漏的用户限制"));assertEquals(3,summary(a).get("omittedHistoryTurns"));
        var withSystem=budget(limits(),List.of(),a).prepare(new Prompt(List.of(new SystemMessage("原有规则"),new UserMessage("当前问题"))),null);
        assertEquals(1,withSystem.getInstructions().stream().filter(m->m instanceof SystemMessage).count());
        assertTrue(withSystem.getContents().contains("原有规则"));
        var b=new ModelUsageLedger();budget(limits(),List.of(),b).prepare(new Prompt("hi"),null);
        assertEquals(0,summary(b).get("omittedHistoryTurns"));a.freeze();
        assertEquals("CHECKED",summary(a).get("status"));
    }
    @Test void growingToolResultsEvictOnlyWholeHistoryPairsAndLeaveMandatoryContentUntouched() {
        List<Message> history=List.of(new UserMessage("水".repeat(60)),new AssistantMessage("冰".repeat(60)),
                new UserMessage("火".repeat(60)),new AssistantMessage("电".repeat(60)));
        var messages=new ArrayList<Message>(history);var current=new UserMessage("当前限制：不使用传说宝可梦");
        var result=new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse("call","lookup","火".repeat(450))));
        messages.add(current);messages.add(result);var ledger=new ModelUsageLedger();
        var prepared=budget(new ContextBudgetProperties(1024,128,800,2,125),history,ledger).prepare(new Prompt(messages),null);
        assertTrue(((Number)summary(ledger).get("omittedHistoryTurns")).intValue()>0);
        assertTrue(prepared.getInstructions().contains(current));assertTrue(prepared.getInstructions().contains(result));
        var recalled=prepared.getInstructions().stream().filter(m->m!=current && m!=result && !(m instanceof SystemMessage)).toList();
        assertEquals(0,recalled.size()%2);assertEquals(6,messages.size());
    }
}
