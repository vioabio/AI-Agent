package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.guides.LocalGuideSearch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GuideReferenceRetentionTest {
    @Test void speciesEvidenceSurvivesALaterGeneralSearchAndSameQueryFailureInvalidatesIt() throws Exception {
        var search = new LocalGuideSearch(mapper,new DefaultResourceLoader(),"classpath:pokemon-strategy/overview-v1.json",directory.resolve("merged.sqlite").toString());
        String speciesArgs = mapper.writeValueAsString(java.util.Map.of("query","杰尼龟 培养 搭配","gameReleaseId","pokemon-scarlet-violet"));
        String generalArgs = mapper.writeValueAsString(java.util.Map.of("query","围绕核心成员构建队伍","gameReleaseId","pokemon-scarlet-violet"));
        String species = mapper.writeValueAsString(search.search("杰尼龟 培养 搭配","pokemon-scarlet-violet").stream().filter(h->h.document().id().equals("squirtle-conditional-overview-v1")).toList());
        String general = mapper.writeValueAsString(search.search("围绕核心成员构建队伍","pokemon-scarlet-violet").stream().filter(h->h.document().id().equals("team-roles-v1")).toList());
        var e = new GuideAnswerEvidence();
        e.begin("请结合杰尼龟的资料和本地攻略，详细讨论休闲单打的培养思路、队伍角色、搭配取舍及需要核对的条件，并列出来源。");
        e.observe("lookupPokemonReference",squirtle());
        e.observe("searchPokemonGuides",species,speciesArgs);
        e.observe("searchPokemonGuides",general,generalArgs);
        String answer = e.verifiedAnswer();
        assertTrue(answer.contains("杰尼龟：休闲单打的培养起点与搭配取舍"));
        assertTrue(answer.contains("围绕核心成员构建队伍"));
        assertTrue(answer.contains("508点分配"));
        assertTrue(answer.indexOf("杰尼龟：休闲单打") < answer.indexOf("围绕核心成员"));
        e.observe("searchPokemonGuides","[tool.error] PRIVATE_PROVIDER_ERROR",generalArgs);
        assertTrue(e.verifiedAnswer().contains("杰尼龟：休闲单打"));
        assertFalse(e.verifiedAnswer().contains("PRIVATE_PROVIDER_ERROR"));
        e.observe("searchPokemonGuides","[]",speciesArgs);
        assertFalse(e.verifiedAnswer().contains("杰尼龟：休闲单打"));
        e.begin("给一条通用原则");
        assertFalse(e.hasGuideSources());
    }
    @Test void unboundMalformedGuideReadCannotKeepOldGuideSources() throws Exception {
        var e=evidence();assertTrue(e.hasGuideSources());
        e.observe("searchPokemonGuides","{}",null);
        assertFalse(e.hasGuideSources());
    }
    @TempDir Path directory;
    final ObjectMapper mapper=new ObjectMapper();
    GuideAnswerEvidence evidence() throws Exception {
        var evidence=new GuideAnswerEvidence();evidence.begin("请介绍杰尼龟并查找组队攻略");
        var search=new LocalGuideSearch(mapper,new DefaultResourceLoader(),"classpath:pokemon-guides/expanded-v1.json",directory.resolve("guides.sqlite").toString());
        evidence.observe("searchPokemonGuides",mapper.writeValueAsString(search.search("围绕核心成员构建队伍","pokemon-scarlet-violet")));
        return evidence;
    }
    String squirtle() throws Exception{return mapper.writeValueAsString(TeamAnswerEvidenceTest.detail(7,"squirtle","杰尼龟","水",43));}
    String input(String query) throws Exception{return mapper.writeValueAsString(java.util.Map.of("query",query,"dataReleaseId","pokeapi-reference-v1"));}
    @Test void failedLaterLookupKeepsActualValidatedMemberAndWarnsWithoutLeakingError() throws Exception {
        var e=evidence();e.observe("lookupPokemonReference",squirtle());e.observe("lookupPokemonReference","[tool.error] private-upstream-detail",input("水箭龟"));
        String answer=e.verifiedAnswer();assertTrue(answer.contains("| 杰尼龟 | 水 | 44 | 48 | 65 | 50 | 64 | 43 |"));
        assertTrue(answer.contains("资料调用失败"));assertFalse(answer.contains("private-upstream-detail"));
    }
    @Test void invalidVersionMalformedAndOversizedLaterPayloadsNeverReplaceEarlierRows() throws Exception {
        var wrong=mapper.readTree(squirtle());((com.fasterxml.jackson.databind.node.ObjectNode)wrong.path("fact")).put("dataReleaseId","other-release").put("speciesNameZh","不可信替换");
        for(String invalid:List.of("{}","[",wrong.toString(),"x".repeat(120001))) {
            var e=evidence();e.observe("lookupPokemonReference",squirtle());e.observe("lookupPokemonReference",invalid,input("9"));
            String answer=e.verifiedAnswer();assertTrue(answer.contains("杰尼龟"));assertFalse(answer.contains("不可信替换"));assertTrue(answer.contains("未通过校验"));
        }
    }
    @Test void failureWithoutAValidReadCannotCreateMemberFactsAndResourceFailuresAreVisible() throws Exception {
        var e=evidence();e.observe("lookupPokemonReference","[tool.denied] secret-policy");e.observe("lookupPokemonResource","[tool.error] private-resource-detail");
        String answer=e.verifiedAnswer();assertFalse(answer.contains("图鉴速览"));assertFalse(answer.contains("| 杰尼龟 |"));assertTrue(answer.contains("未取得的资料不作确认"));
        assertFalse(answer.contains("secret-policy"));assertFalse(answer.contains("private-resource-detail"));
        var good=evidence();good.observe("lookupPokemonResource","{\"kind\":\"ability\",\"dataReleaseId\":\"pokeapi-reference-v1\",\"slug\":\"torrent\"}");
        assertFalse(good.verifiedAnswer().contains("资料读取提醒"));
    }
    @Test void additionalSuccessfulReadSurvivesEarlierFailuresButDoesNotEraseThePartialFailureNotice() throws Exception {
        var e=evidence();e.observe("lookupPokemonReference",squirtle());e.observe("lookupPokemonReference","[tool.error] timeout",input("wartortle"));
        e.observe("lookupPokemonReference",mapper.writeValueAsString(TeamAnswerEvidenceTest.detail(4,"charmander","小火龙","火",65)));
        String answer=e.verifiedAnswer();assertTrue(answer.contains("| 杰尼龟 |"));assertTrue(answer.contains("| 小火龙 |"));assertTrue(answer.contains("资料读取提醒"));
        assertFalse(e.verifiedAnswer(false).contains("图鉴速览"));assertTrue(e.verifiedAnswer(false).contains("资料读取提醒"));
    }
    @Test void sameTargetAliasesAndUnboundFailuresStillInvalidateOldFacts() throws Exception {
        for(String query:List.of("杰尼龟","傑尼龜","squirtle","7","007")) {
            var e=evidence();e.observe("lookupPokemonReference",squirtle());e.observe("lookupPokemonReference","[tool.error] timeout",input(query));
            assertFalse(e.verifiedAnswer().contains("| 杰尼龟 |"),query);
        }
        for(String arguments:java.util.Arrays.asList(null,"{}",input("不在名称表中的对象"))) {
            var e=evidence();e.observe("lookupPokemonReference",squirtle());e.observe("lookupPokemonReference","[tool.error] timeout",arguments);
            assertFalse(e.verifiedAnswer().contains("| 杰尼龟 |"));
        }
    }
    @Test void actualAgentResponseIdsBindTheFailedQueryBeforePreservingAnotherMember() throws Exception {
        var model=org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
        var manager=org.mockito.Mockito.mock(org.springframework.ai.model.tool.ToolCallingManager.class);
        var good=new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("good","function","lookupPokemonReference",input("杰尼龟"));
        var bad=new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("bad","function","lookupPokemonReference",input("水箭龟"));
        var guide=new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("guide","function","searchPokemonGuides","{}");
        java.util.function.Function<org.springframework.ai.chat.messages.AssistantMessage.ToolCall,org.springframework.ai.chat.model.ChatResponse> response=call ->
                new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage("",java.util.Map.of(),List.of(call)))));
        org.mockito.Mockito.when(model.call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(response.apply(good),response.apply(bad),response.apply(guide),new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage("已整理资料")))));
        var search=new LocalGuideSearch(mapper,new DefaultResourceLoader(),"classpath:pokemon-guides/expanded-v1.json",directory.resolve("loop.sqlite").toString());
        var output=java.util.Map.of("good",squirtle(),"bad","[tool.error] private-detail","guide",mapper.writeValueAsString(search.search("围绕核心成员构建队伍","pokemon-scarlet-violet")));
        org.mockito.Mockito.when(manager.executeToolCalls(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            org.springframework.ai.chat.prompt.Prompt prompt=invocation.getArgument(0);org.springframework.ai.chat.model.ChatResponse reply=invocation.getArgument(1);
            var assistant=reply.getResult().getOutput();var call=assistant.getToolCalls().getFirst();
            var messages=new java.util.ArrayList<org.springframework.ai.chat.messages.Message>(prompt.getInstructions());messages.add(assistant);
            messages.add(new org.springframework.ai.chat.messages.ToolResponseMessage(List.of(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(call.id(),call.name(),output.get(call.id())))));
            return new org.springframework.ai.model.tool.DefaultToolExecutionResult(messages,false);
        });
        var agent=new ToolCallAgent(new org.springframework.ai.tool.ToolCallback[0]);agent.setSystemPrompt("Use actual evidence");
        agent.setChatClient(org.springframework.ai.chat.client.ChatClient.builder(model).build());agent.setToolCallingManager(manager);agent.setGuideAnswerEvidence(new GuideAnswerEvidence());
        agent.run("介绍杰尼龟并检索组队攻略");assertTrue(agent.getFinalAnswer().contains("| 杰尼龟 |"));assertTrue(agent.getFinalAnswer().contains("资料读取提醒"));assertFalse(agent.getFinalAnswer().contains("private-detail"));
    }
}
