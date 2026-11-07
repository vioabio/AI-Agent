package com.vio.vioaiagent.agent;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import static org.junit.jupiter.api.Assertions.*;

class ToolHistoryIntegrityTest {
    AssistantMessage assistant(String args) { return new AssistantMessage("private planning",Map.of(),List.of(new AssistantMessage.ToolCall("bad","function","test",args))); }
    ToolResponseMessage response(String id) { return new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse(id,"test","[tool.denied] invalid JSON"))); }
    @Test void completeArgumentsRemainUnchanged() {
        var messages=List.<Message>of(new UserMessage("question"),assistant("{\"nested\":{\"value\":\"}string\"}}"),response("bad"));
        assertEquals(messages,ToolHistoryIntegrity.sanitize(messages));
    }
    @Test void incompleteArgumentsAndTheirResponseAreRemovedAndReplanningIsExplicit() {
        var result=ToolHistoryIntegrity.sanitize(List.of(new UserMessage("original"),assistant("{\"nested\":{}"),response("bad")));
        assertEquals(2,result.size());assertInstanceOf(UserMessage.class,result.getLast());assertTrue(result.getLast().getText().contains("未执行"));
        assertEquals("original",result.getFirst().getText());
    }
    @Test void mixedBatchesKeepValidCallsAndMatchingResponsesTogether() {
        var good=new AssistantMessage.ToolCall("good","function","other","{}");
        var mixed=new AssistantMessage("",Map.of(),List.of(new AssistantMessage.ToolCall("bad","function","test","{"),good));
        var responses=new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse("bad","test","[tool.denied] invalid JSON"),new ToolResponseMessage.ToolResponse("good","other","ok")));
        var result=ToolHistoryIntegrity.sanitize(List.of(mixed,responses));
        assertEquals(List.of(good),((AssistantMessage)result.getFirst()).getToolCalls());
        assertEquals("good",((ToolResponseMessage)result.get(1)).getResponses().getFirst().id());
        assertInstanceOf(UserMessage.class,result.getLast());
    }
    @Test void nonObjectsTrailingTokensAndNullArgumentsCannotPoisonTheNextProviderRequest() {
        for(String args:Arrays.asList("[]","{}{}","null",null))assertFalse(ToolHistoryIntegrity.sanitize(List.of(assistant(args),response("bad"))).stream().anyMatch(m->m instanceof AssistantMessage));
    }
    @Test void anUnconfirmedOrSuccessfulExecutionIsNeverConvertedIntoARetryInstruction() {
        var messages=List.<Message>of(assistant("{"),new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse("bad","test","operation succeeded"))));
        assertEquals(messages,ToolHistoryIntegrity.sanitize(messages));
    }
}
