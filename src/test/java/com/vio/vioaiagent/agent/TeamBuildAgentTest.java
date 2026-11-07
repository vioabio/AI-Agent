package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.pokemon.reference.*;
import com.vio.vioaiagent.run.*;
import com.vio.vioaiagent.security.*;
import com.vio.vioaiagent.tools.governance.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TeamBuildAgentTest {
    @TempDir Path directory;final ObjectMapper json=new ObjectMapper();
    TeamBuildCheckFacts.Request request(BuildCheckFixture f){return new TeamBuildCheckFacts.Request("pokeapi-reference-v1","pokemon-scarlet-violet",List.of(new TeamBuildCheckFacts.Member(1,7,f.request().build()),new TeamBuildCheckFacts.Member(2,7,null)));}
    String run(String role)throws Exception{
        var f=new BuildCheckFixture(directory);String args=json.writeValueAsString(Map.of("request",request(f)));
        var masker=new ParameterMasker();var hitl=new HitlManager();var gateway=new ToolExecutionGateway(hitl,new PathGuard(List.of(directory.toString())),new CommandGuard(List.of()),masker,new AuditLogger(masker));var events=new ArrayList<ToolGovernanceEvent>();var model=mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("",Map.of(),List.of(new AssistantMessage.ToolCall("team-check","function","checkPokemonTeamBuild",args)))))),new ChatResponse(List.of(new Generation(new AssistantMessage("整队已经合法，已保存队伍！")))));
        ToolExecutionContextHolder.set(new ToolExecutionContext("owner","run",Set.of(role),"trace",null,events::add));
        try{
            var callbacks=gateway.wrap(ToolCallbacks.from(new PokemonTeamBuildCheckController.Tools(f.service,null)));var agent=new VioManus(callbacks,null,model);agent.setToolExecutionGateway(gateway);agent.setCurrentUserRequest("请检查整队配置");var deltas=new ArrayList<String>();agent.setAnswerDeltaSink(deltas::add);agent.run("请检查整队配置");
            String answer=agent.getFinalAnswer();assertEquals(answer,String.join("",deltas));assertFalse(answer.contains("整队已经合法"));assertFalse(answer.contains("已保存队伍！"));assertFalse(answer.contains("reportSha256"));
            if(role.equals("trainer"))assertTrue(events.stream().anyMatch(e->"VERIFIED".equals(((Map<?,?>)e.attributes().getOrDefault("outputEvidence",Map.of())).get("status"))));else assertEquals(0,f.networkCalls.get());return answer;
        }finally{ToolExecutionContextHolder.clear();gateway.shutdown();hitl.shutdown();}
    }
    @Test void sdkAndGovernancePublishBoundMembersAndDoNotUseModelLegalityOrSaveClaims()throws Exception{
        String answer=run("trainer");assertTrue(answer.contains("已核对 2 位"));assertTrue(answer.contains("有缺项：1 位"));assertTrue(answer.contains("联合可得性未验证"));assertTrue(answer.contains("第 2 位"));
    }
    @Test void deniedRoleCannotPublishAReferenceOrTriggerReads()throws Exception{String answer=run("unprivileged");assertTrue(answer.contains("未取得与原成员配置绑定"));assertFalse(answer.contains("已核对 2 位"));}
    @Test void wrongRunOwnerIsRejectedBeforeMemberReads(){
        var f=new BuildCheckFixture(directory);var runs=new InMemoryRunRepository();runs.save(AgentRun.create("run-team","session","conversation","alice","test","pokemon-scarlet-violet","casual-singles","casual-v1","zh-CN",null,"pokeapi-reference-v1",null,null,"v1","trace",null,null));
        var tool=new PokemonTeamBuildCheckController.Tools(f.service,new RunToolScope(runs));ToolExecutionContextHolder.set(new ToolExecutionContext("bob","run-team",Set.of("trainer"),"trace",null,null));
        try{assertThrows(IllegalStateException.class,()->tool.checkPokemonTeamBuild(request(f)));assertEquals(0,f.networkCalls.get());}finally{ToolExecutionContextHolder.clear();}
    }
    @Test void changedArgumentsAndForgedJointStateDoNotAcquireOutputEvidence()throws Exception{
        var f=new BuildCheckFixture(directory);var request=request(f);var r=PokemonTeamBuildCheckController.checkMembers(f.service,request);String output=json.writeValueAsString(r),args=json.writeValueAsString(Map.of("request",request));
        assertEquals(r,TeamBuildAnswerEvidence.bound(output,args));assertThrows(IllegalArgumentException.class,()->TeamBuildAnswerEvidence.bound(output,args.replace("\"slot\":2","\"slot\":3")));
        assertThrows(IllegalArgumentException.class,()->TeamBuildAnswerEvidence.bound(output.replace("NOT_VERIFIED","VERIFIED"),args));
        var evidence=new TeamBuildAnswerEvidence();evidence.observe(output,args);assertTrue(evidence.summary("").contains("已核对 2 位"));evidence.observe("{}",args);assertFalse(evidence.summary("").contains("已核对 2 位"));
    }
    @Test void outputCapsuleBindsOriginalArgumentsAndNeverClaimsJointVerification()throws Exception{
        var f=new BuildCheckFixture(directory);var request=request(f);var card=ToolCapabilityCatalog.find("checkPokemonTeamBuild").orElseThrow();var args=json.convertValue(Map.of("request",request),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        var result=TeamBuildResultEvidence.capture(card,args,"digest",json.writeValueAsString(PokemonTeamBuildCheckController.checkMembers(f.service,request)));assertEquals("VERIFIED",result.get("status"));assertEquals("NOT_VERIFIED",result.get("jointStatus"));assertEquals(2,result.get("memberCount"));
        assertEquals("INVALID",TeamBuildResultEvidence.capture(card,args,"digest","{}").get("status"));
    }
    @Test void boundedInputLedgerKeepsItsExistingLimitForSixMembers(){
        var f=new BuildCheckFixture(directory);var one=f.request();var members=new ArrayList<TeamBuildCheckFacts.Member>();for(int slot=1;slot<=6;slot++)members.add(new TeamBuildCheckFacts.Member(slot,7,one.build()));
        var request=new TeamBuildCheckFacts.Request(one.dataReleaseId(),one.gameReleaseId(),members);var args=json.convertValue(Map.of("request",request),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        var evidence=ToolInputEvidence.capture(ToolCapabilityCatalog.find("checkPokemonTeamBuild").orElseThrow(),args,"digest");assertEquals("PARTIAL",evidence.get("coverage"));assertTrue(((Map<?,?>)evidence.get("fields")).size()<=96);
    }
    @Test void teamQuestionHasPriorityOverSingleMemberFallback(){var evidence=new GuideAnswerEvidence();evidence.begin("请检查整队配招");assertTrue(evidence.planningInstruction().contains("checkPokemonTeamBuild"));assertTrue(evidence.verifiedAnswer(true).contains("### 整队配置核对"));assertFalse(evidence.verifiedAnswer(true).contains("### 配招逐项核对"));}
    @Test void explicitOriginalTeamRejectsSubsetReportsEvenWhenTheyBindTheirOwnToolArguments()throws Exception{
        var f=new BuildCheckFixture(directory);var full=request(f);var subset=new TeamBuildCheckFacts.Request(full.dataReleaseId(),full.gameReleaseId(),List.of(full.members().get(0)));
        var evidence=new GuideAnswerEvidence();evidence.begin("请检查整队配置，只核对，不保存。\n"+json.writeValueAsString(full));
        evidence.observe("checkPokemonTeamBuild",json.writeValueAsString(PokemonTeamBuildCheckController.checkMembers(f.service,subset)),json.writeValueAsString(Map.of("request",subset)));
        assertFalse(evidence.verifiedAnswer(true).contains("已核对 1 位"));assertTrue(evidence.verifiedAnswer(true).contains("未取得与原成员配置绑定"));
        evidence.observe("checkPokemonTeamBuild",json.writeValueAsString(PokemonTeamBuildCheckController.checkMembers(f.service,full)),json.writeValueAsString(Map.of("request",full)));
        assertTrue(evidence.verifiedAnswer(true).contains("已核对 2 位"));
    }
}
