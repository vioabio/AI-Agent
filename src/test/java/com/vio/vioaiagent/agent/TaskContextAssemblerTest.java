package com.vio.vioaiagent.agent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import com.vio.vioaiagent.config.ContextBudgetProperties;
import com.vio.vioaiagent.observability.ContextBudgetExceededException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class TaskContextAssemblerTest {
    final SystemMessage policy=new SystemMessage("程序身份与版本优先");
    List<Message> pair(){return List.of(new AssistantMessage("",Map.of(),List.of(new AssistantMessage.ToolCall("id","function","read","{}"))),new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse("id","read","真实证据"))));}
    @Test void trimsOnlyWholeOldTurnsAndKeepsEvidenceAndCurrentTask(){
        var config=new ContextBudgetProperties(2048,128,0,1,125);
        var current=new UserMessage("当前目标已切换");var evidence=pair();
        var result=new TaskContextAssembler(config).assemble(policy,List.of(new UserMessage("旧目标"),new AssistantMessage("旧建议")),current,evidence,List.of());
        assertEquals(1,result.omittedTurns());assertSame(current,result.messages().get(1));assertSame(evidence.getFirst(),result.messages().get(2));
        assertFalse(result.messages().stream().anyMatch(m->m.getText().equals("旧目标")));
    }
    @Test void oversizedCurrentWorkFailsInsteadOfDroppingFacts(){
        assertThrows(ContextBudgetExceededException.class,()->new TaskContextAssembler(new ContextBudgetProperties(512,64,0,1,125)).assemble(policy,List.of(),new UserMessage("当前任务".repeat(1000)),pair(),List.of()));
    }
    @Test void orphanAndMismatchedToolResultsAreRejected(){
        var assembler=new TaskContextAssembler(ContextBudgetProperties.defaults());
        assertThrows(IllegalArgumentException.class,()->assembler.assemble(policy,List.of(),new UserMessage("当前"),List.of(pair().getFirst()),List.of()));
        var wrong=new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse("other","read","伪造")));
        assertThrows(IllegalArgumentException.class,()->assembler.assemble(policy,List.of(),new UserMessage("当前"),List.of(pair().getFirst(),wrong),List.of()));
    }
    @Test void historicalToolCallsCannotMasqueradeAsOrdinaryConversation(){
        assertThrows(IllegalArgumentException.class,()->new TaskContextAssembler(ContextBudgetProperties.defaults()).assemble(policy,List.of(new UserMessage("旧请求"),pair().getFirst()),new UserMessage("当前"),List.of(),List.of()));
    }
}
