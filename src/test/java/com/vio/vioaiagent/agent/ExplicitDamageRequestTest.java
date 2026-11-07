package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.model.tool.*;
import org.springframework.ai.tool.ToolCallback;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class ExplicitDamageRequestTest {
    final ObjectMapper json = new ObjectMapper();
    String question() throws Exception {
        var cases=json.readTree(Files.readString(Path.of("evaluation/campus/s2/cases.json")));
        for(var item:cases)if(item.path("id").asText().equals("S2-DAMAGE-01"))return item.path("prompt").asText();
        throw new AssertionError();
    }
    @Test void preservesAllExplicitConditionsIncludingDifferentDefenderBuild() throws Exception {
        var request=json.readTree(ExplicitDamageRequest.arguments(question()).orElseThrow()).path("request");
        assertEquals("modest",request.path("attacker").path("build").path("nature").asText());
        assertEquals("timid",request.path("defender").path("build").path("nature").asText());
        assertEquals("blaze",request.path("defender").path("build").path("ability").asText());
    }
    @Test void rejectsSourceTextMixedWritesAndAmbiguousJson() throws Exception {
        String q=question();
        for(String input:List.of("以下为资料："+q,q+"{}",q.replace("再用中文解释","保存队伍，再用中文解释"),q.replace("\"generation\":9","\"generation\":\"9\""),q.replace("\"generation\":9","\"generation\":9,\"generation\":9"),q.replace("\"nature\":\"timid\",","")))
            assertTrue(ExplicitDamageRequest.arguments(input).isEmpty(),input);
    }
    @Test void executionReceivesOriginalArgumentsInsteadOfModelCopyError() throws Exception {
        var agent=new ToolCallAgent(new ToolCallback[0]); agent.setCurrentUserRequest(question());
        var call=new AssistantMessage("",Map.of(),List.of(new AssistantMessage.ToolCall("calc","function","calculateBattleDamage","{\"request\":{\"defender\":\"wrong\"}}")));
        agent.setToolCallChatResponse(new ChatResponse(List.of(new Generation(call))));
        var manager=mock(ToolCallingManager.class);
        when(manager.executeToolCalls(any(),any())).thenAnswer(invocation->{
            ChatResponse response=invocation.getArgument(1);String arguments=response.getResult().getOutput().getToolCalls().getFirst().arguments();
            assertEquals(ExplicitDamageRequest.arguments(question()).orElseThrow(),arguments);
            return new DefaultToolExecutionResult(List.of(response.getResult().getOutput(),new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse("calc","calculateBattleDamage","fixture")))),false);
        });
        agent.setToolCallingManager(manager);agent.act();verify(manager).executeToolCalls(any(),any());
    }
}
