package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.guides.*;
import com.vio.vioaiagent.run.*;
import com.vio.vioaiagent.observability.*;
import com.vio.vioaiagent.tools.governance.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GuideResearchAgentTest {
    static final String QUESTION="说明当前项目种族值和努力值的区别";
    @TempDir Path dir;
    final ChatModel model=mock(ChatModel.class);
    final List<ToolGovernanceEvent> events=new ArrayList<>();
    AgentRunContext context;ToolExecutionContext parent;SourceTool source;ToolCallback[] tools;
    @BeforeEach void setup(){
        var run=AgentRun.create("research","s","c","owner",QUESTION,"pokemon-scarlet-violet","casual-singles","casual-v1","zh-CN","trace",null,Instant.now().plusSeconds(30));
        context=new AgentRunContext(run,null,List.of(new UserMessage("PRIVATE_HISTORY_NOT_NEEDED"),new AssistantMessage("unrelated")));
        parent=new ToolExecutionContext("owner",run.id(),Set.of("trainer"),"trace",null,event->{assertNotNull(event.eventId(),"Persisted Run event payloads require a governance event identity");events.add(event);});ToolExecutionContextHolder.set(parent);
        var doc=new LocalGuideSearch.Document("stats","种族值和努力值","pokemon-scarlet-violet","种族值 努力值","VIO project","2026-10-01","","种族值是基础参数，努力值是培养分配。");
        var index=new SqliteGuideKnowledgeRetriever(new ObjectMapper(),List.of(doc),dir.resolve("guide.sqlite").toString());
        var found=index.retrieve(new com.vio.vioaiagent.rag.retrieval.KnowledgeRetrievalQuery("种族值","zh-CN","pokemon-scarlet-violet","casual-singles","casual-v1",5));
        source=new SourceTool(List.of(index.locate(found.getFirst().view().chunk().id()).orElseThrow()));
        tools=ToolCallbacks.from(source);
    }
    public static class SourceTool {
        int reads,writes;final List<LocalGuideSearch.Hit> hits;
        public SourceTool(List<LocalGuideSearch.Hit> hits){this.hits=hits;}
        @Tool(description="Read-only source") public List<LocalGuideSearch.Hit> searchPokemonGuides(String query,String gameReleaseId){reads++;return hits;}
        @Tool(description="Forbidden write fixture") public String applyConfirmedTeamChanges(){writes++;throw new AssertionError("No writes");}
    }
    ChatResponse response(String name,String query,String game){
        String args;try{args=new ObjectMapper().writeValueAsString(Map.of("query",query,"gameReleaseId",game));}catch(Exception e){throw new AssertionError(e);}
        return new ChatResponse(List.of(new Generation(new AssistantMessage("PRIVATE_SPECIALIST_PROSE",Map.of(),List.of(new AssistantMessage.ToolCall("read-source","function",name,args))))));
    }
    @AfterEach void clear(){ToolExecutionContextHolder.clear();Thread.interrupted();}
    @Test void specialistGetsOneReadCapabilityAndReturnsOnlyCanonicalEvidence(){
        when(model.call(any(Prompt.class))).thenAnswer(inv->{
            Prompt prompt=inv.getArgument(0);assertFalse(prompt.getContents().contains("PRIVATE_HISTORY_NOT_NEEDED"));
            var opts=(org.springframework.ai.model.tool.ToolCallingChatOptions)prompt.getOptions();assertEquals(List.of("searchPokemonGuides"),opts.getToolCallbacks().stream().map(t->t.getToolDefinition().name()).toList());
            return response("searchPokemonGuides",QUESTION,"pokemon-scarlet-violet");
        });
        var result=new GuideResearchAgent().research(context,new ObservedChatModel(model,context.modelUsage()),tools);
        assertEquals("EVIDENCE_RETURNED",result.status());assertEquals(1,source.reads);assertEquals(0,source.writes);assertEquals(1,result.evidence().size());assertFalse(result.toString().contains("PRIVATE_SPECIALIST_PROSE"));
        assertSame(parent,ToolExecutionContextHolder.peek());assertEquals(1,context.modelUsage().snapshot().get("modelCallCount"));assertTrue(events.stream().anyMatch(e->e.eventType().equals("agent.delegation.completed")));verify(model,times(1)).call(any(Prompt.class));
    }
    @Test void writesChangedTopicsAndWrongVersionsNeverExecute(){
        for(var answer:List.of(response("applyConfirmedTeamChanges",QUESTION,"pokemon-scarlet-violet"),response("searchPokemonGuides","换个话题","pokemon-scarlet-violet"),response("searchPokemonGuides",QUESTION,"pokemon-sword-shield"))){
            when(model.call(any(Prompt.class))).thenReturn(answer);assertEquals("SINGLE_AGENT_FALLBACK",new GuideResearchAgent().research(context,model,tools).status());
        }
        assertEquals(0,source.reads);assertEquals(0,source.writes);assertSame(parent,ToolExecutionContextHolder.peek());
    }
    @Test void proseOnlyDecisionAndDeniedReadFallBackWithoutPublishingAnything(){
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("I have already edited your team")))));
        assertTrue(new GuideResearchAgent().research(context,model,tools).evidence().isEmpty());assertEquals(0,source.reads);
        var real=Arrays.stream(tools).filter(t->t.getToolDefinition().name().equals("searchPokemonGuides")).findFirst().orElseThrow();var denied=mock(ToolCallback.class);when(denied.getToolDefinition()).thenReturn(real.getToolDefinition());when(denied.call(anyString())).thenReturn("[tool.denied] no access");
        when(model.call(any(Prompt.class))).thenReturn(response("searchPokemonGuides",QUESTION,"pokemon-scarlet-violet"));
        assertTrue(new GuideResearchAgent().research(context,model,new ToolCallback[]{denied}).evidence().isEmpty());
    }
    @Test void cancellationAndGlobalBudgetsAreNotConvertedToSuccessfulFallback(){
        Thread.currentThread().interrupt();assertThrows(java.util.concurrent.CancellationException.class,()->new GuideResearchAgent().research(context,model,tools));Thread.interrupted();verifyNoInteractions(model);
        when(model.call(any(Prompt.class))).thenThrow(new TaskBudgetExceededException(TaskBudgetExceededException.Reason.CALL_LIMIT));
        assertThrows(TaskBudgetExceededException.class,()->new GuideResearchAgent().research(context,model,tools));assertSame(parent,ToolExecutionContextHolder.peek());assertEquals(0,source.reads);
    }
    @Test void missingContextSkipsBeforeAnyModelOrToolCall(){ToolExecutionContextHolder.clear();assertEquals("SKIPPED",new GuideResearchAgent().research(context,model,tools).status());verifyNoInteractions(model);assertEquals(0,source.reads);}
    @Test void supervisorUsesSpecialistEvidenceWithoutRepeatingReadOrPublishingPrivateProse(){
        when(model.call(any(Prompt.class))).thenAnswer(inv->{Prompt prompt=inv.getArgument(0);return prompt.getContents().contains("read-only guide research specialist")?response("searchPokemonGuides",QUESTION,"pokemon-scarlet-violet"):new ChatResponse(List.of(new Generation(new AssistantMessage("PRIVATE_SUPERVISOR_PLAN"))));});
        var executor=new DefaultRunExecutor(model,tools,null,null,null,null,false);executor.setGuideResearchEnabled(true);
        var deltas=new ArrayList<String>();var ctx=new AgentRunContext(context.run(),deltas::add,List.of());
        String answer=executor.execute(ctx);
        assertTrue(answer.contains("种族值是基础参数"));assertFalse(answer.contains("PRIVATE_"));assertEquals(answer,String.join("",deltas));assertEquals(1,source.reads);assertEquals(0,source.writes);assertEquals(2,ctx.modelUsage().snapshot().get("modelCallCount"));
    }
}
