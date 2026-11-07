package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResourceAgentAnswerTest {
    String run(String result) {
        var model=mock(ChatModel.class);var manager=mock(ToolCallingManager.class);
        var call=new AssistantMessage.ToolCall("resource","function","lookupPokemonResource","{\"kind\":\"nature\",\"slug\":\"timid\",\"dataReleaseId\":\"pokeapi-reference-v1\"}");
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("",Map.of(),List.of(call))))),new ChatResponse(List.of(new Generation(new AssistantMessage("错误模型说明：胆小性格提升特攻。")))));
        when(manager.executeToolCalls(any(),any())).thenAnswer(invocation->{Prompt prompt=invocation.getArgument(0);ChatResponse reply=invocation.getArgument(1);var messages=new ArrayList<Message>(prompt.getInstructions());messages.add(reply.getResult().getOutput());messages.add(new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse("resource","lookupPokemonResource",result))));return new DefaultToolExecutionResult(messages,false);});
        var agent=new ToolCallAgent(new org.springframework.ai.tool.ToolCallback[0]);agent.setSystemPrompt("Use actual resource facts");agent.setChatClient(org.springframework.ai.chat.client.ChatClient.builder(model).build());agent.setToolCallingManager(manager);agent.setGuideAnswerEvidence(new GuideAnswerEvidence());
        var chunks=new ArrayList<String>();agent.setAnswerDeltaSink(chunks::add);agent.run("请查询胆小性格的能力变化");verify(model,times(2)).call(any(Prompt.class));assertEquals(agent.getFinalAnswer(),String.join("",chunks));return agent.getFinalAnswer();
    }
    @Test void directResourceQuestionUsesTheActualReadInsteadOfModelMechanismClaims() throws Exception {
        String resource=null;for(var row:new ObjectMapper().readTree(Path.of("src/main/resources/pokemon-mechanics/references-v1.json").toFile()).path("records"))if(row.path("slug").asText().equals("timid"))resource=row.toString();
        String answer=run(resource);assertTrue(answer.contains("提升速度，降低攻击"));assertFalse(answer.contains("错误模型说明"));assertFalse(answer.contains("胆小性格提升特攻"));
    }
    @Test void failedResourceReadCannotFallBackToUnsupportedModelMechanisms() {
        String answer=run("[tool.error] private-upstream-detail");assertTrue(answer.contains("本次未取得可校验的机制字段"));assertFalse(answer.contains("错误模型说明"));assertFalse(answer.contains("private-upstream-detail"));
    }
}
