package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vio.vioaiagent.pokemon.domain.PokemonFact;
import com.vio.vioaiagent.pokemon.guides.LocalGuideSearch;
import com.vio.vioaiagent.pokemon.reference.LocalPokeApiRepository;
import com.vio.vioaiagent.security.*;
import com.vio.vioaiagent.tools.governance.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.*;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpeciesGuidePreflightTest {
    @TempDir Path directory;
    final ObjectMapper mapper=new ObjectMapper();
    static final String QUESTION="请介绍雪妖女并给出培养搭配";
    void exercise(String role,boolean alreadyFocused,boolean authoritativeVersion,boolean exposed) throws Exception {
        var index=new LocalGuideSearch(mapper,new DefaultResourceLoader(),"classpath:pokemon-strategy/overview-v1.json",directory.resolve(UUID.randomUUID()+".sqlite").toString());
        var search=new ProjectGuidePreflightTest.Search(index);var hitl=new HitlManager();var masker=new ParameterMasker();
        var gateway=new ToolExecutionGateway(hitl,new PathGuard(List.of(directory.toString())),new CommandGuard(List.of()),masker,new AuditLogger(masker));
        var events=new ArrayList<ToolGovernanceEvent>();
        ToolExecutionContextHolder.set(new ToolExecutionContext("fixture-owner","run-species-source",Set.of(role),"fixture-trace",null,events::add));
        try {
            var tools=Arrays.stream(ToolCallbacks.from(search)).map(gateway::wrap).toArray(org.springframework.ai.tool.ToolCallback[]::new);
            var model=mock(ChatModel.class);var manager=mock(ToolCallingManager.class);
            var reference=new AssistantMessage.ToolCall("ref","function","lookupPokemonReference","{\"query\":\"雪妖女\",\"dataReleaseId\":\"pokeapi-reference-v1\"}");
            var guide=new AssistantMessage.ToolCall("guide","function","searchPokemonGuides","{\"query\":\"培养搭配策略\",\"gameReleaseId\":\"pokemon-scarlet-violet\"}");
            when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("",Map.of(),List.of(reference))))),
                    new ChatResponse(List.of(new Generation(new AssistantMessage("",Map.of(),List.of(guide))))));
            var fact=(ObjectNode)mapper.readTree(Path.of("evaluation/campus/user-reports-v1/fixtures.json").toFile()).path("references").path("froslass").path("fact");
            fact.put("formKey","froslass").put("formNameZh","雪妖女");
            var outputs=Map.of("ref",mapper.writeValueAsString(new LocalPokeApiRepository.Detail(mapper.treeToValue(fact,PokemonFact.class),List.of(),List.of(),"2026-10-23","本地参考")),
                    "guide",mapper.writeValueAsString(index.search(alreadyFocused?QUESTION:"培养搭配策略","pokemon-scarlet-violet")));
            when(manager.executeToolCalls(any(),any())).thenAnswer(call->{
                Prompt prompt=call.getArgument(0);ChatResponse response=call.getArgument(1);var assistant=response.getResult().getOutput();var tool=assistant.getToolCalls().getFirst();
                var messages=new ArrayList<Message>(prompt.getInstructions());messages.add(assistant);messages.add(new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse(tool.id(),tool.name(),outputs.get(tool.id())))));
                return new DefaultToolExecutionResult(messages,false);
            });
            var agent=new ToolCallAgent(exposed?tools:new org.springframework.ai.tool.ToolCallback[0]);agent.setSystemPrompt("Use actual sources");agent.setChatClient(org.springframework.ai.chat.client.ChatClient.builder(model).build());
            var evidence=new GuideAnswerEvidence();
            if(alreadyFocused){evidence.begin(QUESTION);evidence.observe("searchPokemonGuides",outputs.get("guide"));}
            agent.setToolCallingManager(manager);agent.setGuideAnswerEvidence(evidence);agent.setToolExecutionGateway(gateway);
            agent.setGuideGameReleaseId(authoritativeVersion?"pokemon-scarlet-violet":null);agent.setCurrentUserRequest(QUESTION);
            agent.run(QUESTION);verify(model,times(alreadyFocused||authoritativeVersion&&exposed&&role.equals("trainer")?1:2)).call(any(Prompt.class));
            boolean triggered=!alreadyFocused&&authoritativeVersion&&exposed;
            assertEquals(triggered&&role.equals("trainer")?1:0,search.calls.get());
            if(triggered){assertFalse(events.isEmpty());assertTrue(events.stream().allMatch(e->GuideAnswerEvidence.SUBJECT_SOURCE_ORIGIN.equals(e.attributes().get("selectionOrigin"))));}
            else assertTrue(events.isEmpty());
            if(alreadyFocused||triggered&&role.equals("trainer"))assertTrue(agent.getFinalAnswer().contains("特攻努力值252、速度252、HP 4"));
            else assertFalse(agent.getFinalAnswer().contains("特攻努力值252、速度252、HP 4"));
            if(triggered&&role.equals("trainer")) {
                evidence.observe("searchPokemonGuides","[]",mapper.writeValueAsString(Map.of(
                        "query","通用组队角色","gameReleaseId","pokemon-scarlet-violet")));
                assertTrue(evidence.verifiedAnswer().contains("特攻努力值252、速度252、HP 4"));
            }
        }finally{ToolExecutionContextHolder.clear();gateway.shutdown();hitl.shutdown();}
    }
    @Test void missingModelSubjectGetsOneMarkedGovernedSourceReadWithoutAnotherModelCall() throws Exception {exercise("trainer",false,true,true);}
    @Test void alreadyFocusedSourcesAvoidTheProgramRead() throws Exception {exercise("trainer",true,true,true);}
    @Test void programReadCannotBypassRoleCapabilityOrAuthoritativeVersion() throws Exception {
        exercise("unprivileged",false,true,true);exercise("trainer",false,false,true);exercise("trainer",false,true,false);
    }
}
