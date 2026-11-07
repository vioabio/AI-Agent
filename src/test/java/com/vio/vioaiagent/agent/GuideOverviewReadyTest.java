package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.guides.LocalGuideSearch;
import com.vio.vioaiagent.pokemon.reference.LocalPokeApiRepository;
import com.vio.vioaiagent.pokemon.domain.PokemonFact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.*;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GuideOverviewReadyTest {
    @TempDir Path directory;
    final ObjectMapper mapper=new ObjectMapper();
    static final String QUESTION="请介绍雪妖女，并结合所选赛制给出培养与搭配建议。请用中文区分资料事实和策略建议；资料不足时说明缺失，不编造当前热门阵容。";
    String fact() throws Exception {
        var tree=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(Path.of("evaluation/campus/user-reports-v1/fixtures.json").toFile()).path("references").path("froslass").path("fact");
        tree.put("formKey",tree.path("slug").asText()).put("formNameZh",tree.path("speciesNameZh").asText());
        var f=mapper.treeToValue(tree,PokemonFact.class);
        return mapper.writeValueAsString(new LocalPokeApiRepository.Detail(f,List.of(),List.of(),"2026-10-23","参考资料"));
    }
    String guides() throws Exception {
        var index=new LocalGuideSearch(mapper,new DefaultResourceLoader(),"classpath:pokemon-guides/expanded-v1.json",directory.resolve("guides.sqlite").toString());
        return mapper.writeValueAsString(index.search("培养配置与个体值","pokemon-scarlet-violet"));
    }
    @Test void readinessNeedsActualSpeciesAndSourcesAndExcludesOtherTasksOrBackground() throws Exception {
        var e=new GuideAnswerEvidence();e.begin(QUESTION);e.observe("lookupPokemonReference",fact());assertFalse(e.overviewReady(QUESTION));
        e.observe("searchPokemonGuides",guides());assertTrue(e.overviewReady(QUESTION));
        for(String question:List.of("你好\n已保存背景："+QUESTION,"请介绍雪妖女并计算伤害和培养搭配","请介绍雪妖女并修改队伍搭配","比较雪妖女和杰尼龟的培养策略","请介绍雪妖女并给出完整配招搭配","请介绍雪妖女与另一只相比的培养搭配","请介绍froslass-other并给出培养搭配"))assertFalse(e.overviewReady(question),question);
        e.observe("lookupPokemonReference","[tool.error] unavailable","{\"query\":\"478\",\"dataReleaseId\":\"pokeapi-reference-v1\"}");assertFalse(e.overviewReady(QUESTION));
        var noFact=new GuideAnswerEvidence();noFact.begin(QUESTION);noFact.observe("searchPokemonGuides",guides());assertFalse(noFact.overviewReady(QUESTION));
    }
    record Loop(ToolCallAgent agent,ChatModel model) { }
    Loop loop(boolean damage) throws Exception {
        var model=mock(ChatModel.class);var manager=mock(ToolCallingManager.class);
        var ref=new AssistantMessage.ToolCall("ref","function","lookupPokemonReference","{\"query\":\"雪妖女\",\"dataReleaseId\":\"pokeapi-reference-v1\"}");
        var guide=new AssistantMessage.ToolCall("guide","function","searchPokemonGuides","{}");
        var first=new ChatResponse(List.of(new Generation(new AssistantMessage("",Map.of(),List.of(ref)))));
        var second=new ChatResponse(List.of(new Generation(new AssistantMessage("",Map.of(),List.of(guide)))));
        when(model.call(any(Prompt.class))).thenReturn(first,second,new ChatResponse(List.of(new Generation(new AssistantMessage("已有资料")))));
        var outputs=Map.of("ref",fact(),"guide",guides());
        when(manager.executeToolCalls(any(),any())).thenAnswer(call->{
            Prompt prompt=call.getArgument(0);ChatResponse response=call.getArgument(1);var assistant=response.getResult().getOutput();var tool=assistant.getToolCalls().getFirst();
            var messages=new ArrayList<Message>(prompt.getInstructions());messages.add(assistant);messages.add(new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse(tool.id(),tool.name(),outputs.get(tool.id())))));
            return new DefaultToolExecutionResult(messages,false);
        });
        var agent=new ToolCallAgent(new org.springframework.ai.tool.ToolCallback[0]);agent.setSystemPrompt("Use actual sources");agent.setChatClient(org.springframework.ai.chat.client.ChatClient.builder(model).build());
        agent.setToolCallingManager(manager);agent.setGuideAnswerEvidence(new GuideAnswerEvidence());
        if(damage){var evidence=new DamageAnswerEvidence();evidence.begin("请计算实际伤害");agent.setDamageAnswerEvidence(evidence);}
        return new Loop(agent,model);
    }
    @Test void verifiedOverviewPublishesBoundedAnswerWithoutAnotherPlanningCall() throws Exception {
        var loop=loop(false);var chunks=new ArrayList<String>();loop.agent.setAnswerDeltaSink(chunks::add);String transcript=loop.agent.run(QUESTION);
        assertTrue(loop.agent.getFinalAnswer().contains("雪妖女"));assertTrue(loop.agent.getFinalAnswer().contains("本次范围"));
        assertTrue(loop.agent.getFinalAnswer().contains("不足以确认物种专属的完整配招"));assertEquals(loop.agent.getFinalAnswer(),String.join("",chunks));
        assertTrue(transcript.contains("本次范围"));
        verify(loop.model,times(2)).call(any(Prompt.class));
    }
    @Test void anotherActiveEvidenceGateCannotBeBypassedByAvailableOverviewSources() throws Exception {
        var loop=loop(true);loop.agent.run(QUESTION);
        verify(loop.model,times(3)).call(any(Prompt.class));assertFalse(loop.agent.getFinalAnswer().contains("本次范围"));
    }
}
