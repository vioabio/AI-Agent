package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.guides.LocalGuideSearch;
import com.vio.vioaiagent.pokemon.reference.*;
import com.vio.vioaiagent.security.*;
import com.vio.vioaiagent.tools.governance.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.*;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OverviewResourcePreflightTest {
    @TempDir Path directory;
    public static class Reads {
        LocalPokeApiRepository.Detail detail;LocalGuideSearch search;LocalResourceReferences resources;String mode="normal";int guides;
        final List<String> resourceCalls=new ArrayList<>();
        @Tool(description="Read reference facts") public LocalPokeApiRepository.Detail lookupPokemonReference(@ToolParam(description="query")String query,@ToolParam(description="release")String dataReleaseId){return detail;}
        @Tool(description="Read source guides") public List<LocalGuideSearch.Hit> searchPokemonGuides(@ToolParam(description="query")String query,@ToolParam(description="game")String gameReleaseId){guides++;return search.search(query,gameReleaseId);}
        @Tool(description="Read resource facts") public Map<String,Object> lookupPokemonResource(@ToolParam(description="kind")String kind,@ToolParam(description="slug")String slug,@ToolParam(description="release")String dataReleaseId){
            resourceCalls.add(kind+":"+slug);if(mode.equals("move-fails")&&kind.equals("move"))throw new IllegalStateException("private-test-error");return resources.read(kind,slug,dataReleaseId);}
    }
    record Result(String answer,List<String> calls,List<ToolGovernanceEvent> events,int modelCalls) { }
    Result run(boolean includeAbility,String mode,boolean exposeResource,boolean version,boolean damage) throws Exception {
        var masker=new ParameterMasker();var hitl=new HitlManager();var gateway=new ToolExecutionGateway(hitl,new PathGuard(List.of(directory.toString())),new CommandGuard(List.of()),masker,new AuditLogger(masker));var events=new ArrayList<ToolGovernanceEvent>();
        ToolExecutionContextHolder.set(new ToolExecutionContext("fixture-owner","run-overview-resources",Set.of(mode.equals("denied")?"unprivileged":"trainer"),"trace",null,events::add));
        try {
            var fixture=new Reads();fixture.detail=new OverviewResourceCoverageTest().member();fixture.mode=mode;
            fixture.search=new LocalGuideSearch(new ObjectMapper(),new DefaultResourceLoader(),"classpath:pokemon-strategy/overview-v1.json",directory.resolve(UUID.randomUUID()+".sqlite").toString());
            fixture.resources=new LocalResourceReferences(new ObjectMapper(),new DefaultResourceLoader(),new PokeApiClient(new ObjectMapper(),directory.resolve("public-cache"),url->{throw new AssertionError("Unexpected network");}));
            ToolCallback[] all=Arrays.stream(ToolCallbacks.from(fixture)).map(gateway::wrap).filter(t->exposeResource||!t.getToolDefinition().name().equals("lookupPokemonResource")).toArray(ToolCallback[]::new);
            var calls=new ArrayList<AssistantMessage.ToolCall>();calls.add(new AssistantMessage.ToolCall("ref","function","lookupPokemonReference","{\"query\":\"雪妖女\",\"dataReleaseId\":\"pokeapi-reference-v1\"}"));
            if(includeAbility)calls.add(new AssistantMessage.ToolCall("ability","function","lookupPokemonResource","{\"kind\":\"ability\",\"slug\":\"snow-cloak\",\"dataReleaseId\":\"pokeapi-reference-v1\"}"));
            if(mode.equals("denied")){calls.clear();calls.add(new AssistantMessage.ToolCall("denied","function","lookupPokemonResource","{\"kind\":\"item\",\"slug\":\"leftovers\",\"dataReleaseId\":\"pokeapi-reference-v1\"}"));}
            var model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("",Map.of(),calls)))),new ChatResponse(List.of(new Generation(new AssistantMessage("unverified model mechanism")))));
            var agent=new VioManus(all,null,model);agent.setToolExecutionGateway(gateway);agent.setGuideGameReleaseId(version?"pokemon-scarlet-violet":null);agent.setCurrentUserRequest(OverviewResourceCoverageTest.QUESTION);
            if(mode.equals("denied")){var seeded=new GuideAnswerEvidence();seeded.begin(OverviewResourceCoverageTest.QUESTION);seeded.observe("lookupPokemonReference",new ObjectMapper().writeValueAsString(fixture.detail));seeded.observe("searchPokemonGuides",new ObjectMapper().writeValueAsString(fixture.search.search(OverviewResourceCoverageTest.QUESTION,"pokemon-scarlet-violet")));agent.setGuideAnswerEvidence(seeded);}
            if(damage){var guard=new DamageAnswerEvidence();guard.begin("请计算实际伤害");agent.setDamageAnswerEvidence(guard);}
            var deltas=new ArrayList<String>();agent.setAnswerDeltaSink(deltas::add);agent.run(OverviewResourceCoverageTest.QUESTION);
            assertEquals(agent.getFinalAnswer(),String.join("",deltas));int count=mockingDetails(model).getInvocations().stream().filter(i->i.getMethod().getName().equals("call")).toList().size();
            return new Result(agent.getFinalAnswer(),List.copyOf(fixture.resourceCalls),List.copyOf(events),count);
        }finally{ToolExecutionContextHolder.clear();gateway.shutdown();hitl.shutdown();}
    }
    @Test void bothCategoriesAreReadThroughTheGatewayAndPublishedWithoutAnotherModelCall() throws Exception {
        var r=run(false,"normal",true,true,false);assertEquals(List.of("ability:snow-cloak","move:ice-beam"),r.calls());assertEquals(1,r.modelCalls());
        assertTrue(r.answer().contains("雪隐（特性）"));assertTrue(r.answer().contains("冰冻光束（招式）"));assertTrue(r.answer().contains("特性：已取得"));assertTrue(r.answer().contains("招式：已取得"));
        assertEquals(2,r.events().stream().filter(e->e.eventType().equals("tool.started")&&OverviewResourceCoverage.ORIGIN.equals(e.attributes().get("selectionOrigin"))).count());
    }
    @Test void existingAbilityIsNotQueriedTwiceAndMissingMoveAloneIsFilled() throws Exception {
        var r=run(true,"normal",true,true,false);assertEquals(List.of("ability:snow-cloak","move:ice-beam"),r.calls());assertEquals(1,r.modelCalls());
        assertEquals(1,r.events().stream().filter(e->e.eventType().equals("tool.started")&&OverviewResourceCoverage.ORIGIN.equals(e.attributes().get("selectionOrigin"))).count());
    }
    @Test void failedMoveRetainsTheAbilityAndListsTheGapWithoutExposingRawErrorsOrLooping() throws Exception {
        var r=run(false,"move-fails",true,true,false);assertEquals(1,r.modelCalls());assertEquals(3,r.calls().size());
        assertTrue(r.answer().contains("雪隐（特性）"));assertFalse(r.answer().contains("冰冻光束（招式）"));assertTrue(r.answer().contains("招式：未取得"));assertFalse(r.answer().contains("private-test-error"));
    }
    @Test void absentCapabilityUnknownVersionAndActiveDamageCannotCreateImplicitResourceReads() throws Exception {
        var absent=run(false,"normal",false,true,false);assertTrue(absent.calls().isEmpty());assertTrue(absent.answer().contains("当前工具或可信运行范围不可用"));
        var unknown=run(false,"normal",true,false,false);assertTrue(unknown.calls().isEmpty());assertFalse(unknown.answer().contains("unverified model mechanism"));
        var guarded=run(false,"normal",true,true,true);assertTrue(guarded.calls().isEmpty());
    }
    @Test void anUnprivilegedContextCannotUseProgramReadsEvenWithPreviouslyValidatedSources() throws Exception {
        var denied=run(false,"denied",true,true,false);assertTrue(denied.calls().isEmpty());assertEquals(1,denied.modelCalls());
        assertTrue(denied.answer().contains("特性：未取得"));assertTrue(denied.answer().contains("招式：未取得"));
        assertFalse(denied.events().stream().anyMatch(e->e.eventType().equals("tool.started")));
        assertTrue(denied.events().stream().anyMatch(e->OverviewResourceCoverage.ORIGIN.equals(e.attributes().get("selectionOrigin"))));
    }
}
