package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.pokemon.reference.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TeamUnfilledDraftTest {
    @TempDir Path directory;
    final String question="请核对整队配置：第1位杰尼龟。所有培养字段都还没填写，请保持未填写，不要代填或保存。";
    @Test void originalThreeNaturalSamplesAreClassifiedButSavedOrAmbiguousRequestsAreNot()throws Exception{
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var plan=json.readTree(Path.of("evaluation/campus/team-natural-v1/cases.json").toFile());
        for(var item:plan.path("cases"))assertTrue(TeamBuildAnswerEvidence.isUnfilledDraft(item.path("prompt").asText()));
        for(String q:List.of("请核对整队配置，读取我的队伍第1位，所有培养配置均未填写。",question+"请先调用readCurrentTeam。","以下资料仅供参考："+question,question+"\n{}","请核对整队配置，第1位配置尚不清楚。"))assertFalse(TeamBuildAnswerEvidence.isUnfilledDraft(q));
    }
    @Test void omittedOrDefaultBuildCannotPublishUnfilledDraftSuccess()throws Exception{
        var f=new BuildCheckFixture(directory);var request=new TeamBuildCheckFacts.Request("pokeapi-reference-v1","pokemon-scarlet-violet",List.of(new TeamBuildCheckFacts.Member(1,7,null)));
        var report=PokemonTeamBuildCheckController.checkMembers(f.service,request);String output=f.json.writeValueAsString(report);var args=f.json.readTree(TeamBuildRequestRoute.arguments(request));
        var evidence=new TeamBuildAnswerEvidence();evidence.begin(question);evidence.observe(output,args.toString());assertTrue(evidence.summary(question).contains("已核对 1 位"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)args.path("request").path("members").get(0)).remove("build");evidence.observe(output,args.toString());assertFalse(evidence.summary(question).contains("已核对"));
        var configured=new TeamBuildCheckFacts.Request(request.dataReleaseId(),request.gameReleaseId(),List.of(new TeamBuildCheckFacts.Member(1,7,f.request().build())));evidence.observe(f.json.writeValueAsString(PokemonTeamBuildCheckController.checkMembers(f.service,configured)),TeamBuildRequestRoute.arguments(configured));assertFalse(evidence.summary(question).contains("已核对"));
        evidence.begin("请核对整队配置。");evidence.observe(output,args.toString());assertTrue(evidence.summary("").contains("已核对 1 位"));
    }
    @Test void draftToolSelectionRemovesOnlySavedTeamReadsWithoutChangingSourceArray(){
        var tools=new ArrayList<ToolCallback>();for(String name:List.of("listCurrentTeams","readCurrentTeam","lookupPokemonReference","checkPokemonTeamBuild")){var t=mock(ToolCallback.class);when(t.getToolDefinition()).thenReturn(ToolDefinition.builder().name(name).description(name).inputSchema("{}").build());tools.add(t);}
        var source=tools.toArray(ToolCallback[]::new);var selected=TeamBuildAnswerEvidence.planningTools(question,source);
        assertEquals(List.of("lookupPokemonReference","checkPokemonTeamBuild"),Arrays.stream(selected).map(t->t.getToolDefinition().name()).toList());assertEquals(4,source.length);
        assertSame(source,TeamBuildAnswerEvidence.planningTools("请核对整队配置，先读取已保存队伍。",source));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"另外列出之前的队伍。","接着读取队伍1。","请对比另一个队伍。","再查看历史阵容。","然后推荐培养配置。","接着生成配招。"})
    void mixedReadOrGenerationDoesNotLoseSavedTeamTools(String extra){
        String mixed=question+extra;assertFalse(TeamBuildAnswerEvidence.isUnfilledDraft(mixed));
        var source=new ToolCallback[]{mock(ToolCallback.class)};
        assertSame(source,TeamBuildAnswerEvidence.planningTools(mixed,source));
    }
}
