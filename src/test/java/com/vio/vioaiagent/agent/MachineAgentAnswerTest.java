package com.vio.vioaiagent.agent;
import com.vio.vioaiagent.pokemon.reference.*;
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
class MachineAgentAnswerTest {
    @TempDir Path directory;
    String run(String role){return run(role,"查询杰尼龟冰冻光束的学习器编号");}
    String run(String role,String request){var f=new BuildCheckFixture(directory);var masker=new ParameterMasker();var hitl=new HitlManager();var gateway=new ToolExecutionGateway(hitl,new PathGuard(List.of(directory.toString())),new CommandGuard(List.of()),masker,new AuditLogger(masker));var events=new ArrayList<ToolGovernanceEvent>();var model=mock(ChatModel.class);
        String args="{\"pokemonId\":7,\"moveSlug\":\"ice-beam\",\"dataReleaseId\":\"pokeapi-reference-v1\",\"gameReleaseId\":\"pokemon-scarlet-violet\"}";
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("",Map.of(),List.of(new AssistantMessage.ToolCall("tm","function","lookupPokemonMachine",args)))))),new ChatResponse(List.of(new Generation(new AssistantMessage("错误说明：TM999，在假想地点获得，已保存队伍")))));
        ToolExecutionContextHolder.set(new ToolExecutionContext("owner","run",Set.of(role),"trace",null,events::add));
        try{var tools=gateway.wrap(ToolCallbacks.from(new PokemonMachineController.Tools(new LocalMachineReferences(f.learning),null)));var agent=new VioManus(tools,null,model);agent.setToolExecutionGateway(gateway);agent.setCurrentUserRequest(request);var deltas=new ArrayList<String>();agent.setAnswerDeltaSink(deltas::add);agent.run(request);assertEquals(agent.getFinalAnswer(),String.join("",deltas));assertFalse(agent.getFinalAnswer().contains("TM999"));assertFalse(agent.getFinalAnswer().contains("假想地点"));if(role.equals("trainer"))assertTrue(events.stream().anyMatch(e->"VERIFIED".equals(((Map<?,?>)e.attributes().getOrDefault("outputEvidence",Map.of())).get("status"))));else assertEquals(0,f.networkCalls.get());return agent.getFinalAnswer();}finally{ToolExecutionContextHolder.clear();gateway.shutdown();hitl.shutdown();}}
    @Test void actualGovernedResultReplacesModelInventedIdentifierAndAcquisition(){String answer=run("trainer");assertTrue(answer.contains("TM135"));assertTrue(answer.contains("未核验获取地点"));assertFalse(answer.contains("reportSha256"));}
    @Test void deniedRoleCannotPublishAMachineIdentifier(){String answer=run("unprivileged");assertTrue(answer.contains("未取得与成员和招式绑定"));assertFalse(answer.contains("TM135"));}
    @Test void explicitRecipeQuestionUsesGovernedFactsAndSourceBoundSupplement(){
        assertTrue(MachineAnswerEvidence.requested("查询杰尼龟冰冻光束的学习器配方"));
        String answer=run("trainer","查询杰尼龟冰冻光束的学习器配方");
        assertTrue(answer.contains("Cryogonal Ice × 5"));assertTrue(answer.contains("待人工及游戏内核验"));assertFalse(answer.contains("catalogSha256"));
    }
    @Test void explicitAcquisitionQuestionDoesNotInventASingleSourceLocationOrUnlock(){
        assertTrue(MachineAnswerEvidence.requested("查询杰尼龟冰冻光束的学习器获取位置"));
        String answer=run("trainer","查询杰尼龟冰冻光束的学习器获取位置");
        assertTrue(answer.contains("获取区域线索"));assertTrue(answer.contains("未取得可交叉对应的区域资料"));assertFalse(answer.contains("Eri"));assertFalse(answer.contains("Glaseado"));
    }
}
