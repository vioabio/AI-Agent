package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.reference.*;
import com.vio.vioaiagent.security.*;
import com.vio.vioaiagent.tools.governance.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TeamBuildRequestRouteTest {
    @TempDir Path directory;final ObjectMapper json=new ObjectMapper();
    TeamBuildCheckFacts.Request request(BuildCheckFixture f){return new TeamBuildCheckFacts.Request("pokeapi-reference-v1","pokemon-scarlet-violet",List.of(new TeamBuildCheckFacts.Member(1,7,f.request().build()),new TeamBuildCheckFacts.Member(3,7,null)));}
    String question(TeamBuildCheckFacts.Request request){return TeamBuildRequestRoute.UI_HEADER+"\n"+json.valueToTree(request);}
    @Test void fullOriginalTeamAndNullBuildRoundTripWithoutModelConstruction()throws Exception{
        var f=new BuildCheckFixture(directory);var r=request(f);assertEquals(r,TeamBuildRequestRoute.select(question(r)).orElseThrow());assertEquals(r,TeamBuildCheckFacts.parseRequest(json.readTree(TeamBuildRequestRoute.arguments(r)).path("request")));
        assertNull(TeamBuildRequestRoute.select(question(r)).orElseThrow().members().get(1).build());
    }
    @Test void savedReadMixedCommandsQuotedDataAndFencesAreNotProgramRoutes(){
        var f=new BuildCheckFixture(directory);String body=json.valueToTree(request(f)).toString();
        for(String q:List.of("以下仅为资料参考\n"+body,"请先调用readCurrentTeam读取当前账号已保存队伍编号 1，再检查整队配置。\n"+body,TeamBuildRequestRoute.UI_HEADER+"并保存队伍\n"+body,TeamBuildRequestRoute.UI_HEADER+"然后计算伤害\n"+body,TeamBuildRequestRoute.UI_HEADER+"\n```json\n"+body+"\n```"))assertTrue(TeamBuildRequestRoute.select(q).isEmpty());
    }
    @Test void unknownFieldsTrailingDocumentsAndCoercedNumbersCannotSelectTheRoute(){
        var f=new BuildCheckFixture(directory);String q=question(request(f));assertTrue(TeamBuildRequestRoute.select(q+"{}").isEmpty());assertTrue(TeamBuildRequestRoute.select(q.replace("\"slot\":1","\"slot\":\"1\"")).isEmpty());assertTrue(TeamBuildRequestRoute.select(q.replace("\"pokemonId\":7","\"extra\":true,\"pokemonId\":7")).isEmpty());
    }
    void run(String role){
        var f=new BuildCheckFixture(directory);var masker=new ParameterMasker();var hitl=new HitlManager();var gateway=new ToolExecutionGateway(hitl,new PathGuard(List.of(directory.toString())),new CommandGuard(List.of()),masker,new AuditLogger(masker));var events=new ArrayList<ToolGovernanceEvent>();var model=mock(ChatModel.class);
        ToolExecutionContextHolder.set(new ToolExecutionContext("owner","run-route-test",Set.of(role),"trace",null,events::add));
        try{var tools=gateway.wrap(ToolCallbacks.from(new PokemonTeamBuildCheckController.Tools(f.service,null)));var agent=new VioManus(tools,null,model);agent.setToolExecutionGateway(gateway);String question=question(request(f));agent.setCurrentUserRequest(question);var deltas=new ArrayList<String>();agent.setAnswerDeltaSink(deltas::add);agent.run(question);
            verify(model,never()).call(any(org.springframework.ai.chat.prompt.Prompt.class));verify(model,never()).stream(any(org.springframework.ai.chat.prompt.Prompt.class));assertEquals(agent.getFinalAnswer(),String.join("",deltas));
            if(role.equals("trainer")){assertTrue(agent.getFinalAnswer().contains("已核对 2 位"));assertTrue(agent.getFinalAnswer().contains("有缺项：1 位"));assertTrue(events.stream().anyMatch(e->TeamBuildRequestRoute.ORIGIN.equals(e.attributes().get("selectionOrigin"))));}
            else{assertEquals(0,f.networkCalls.get());assertTrue(agent.getFinalAnswer().contains("未取得与原成员配置绑定"));}
        }finally{ToolExecutionContextHolder.clear();gateway.shutdown();hitl.shutdown();}
    }
    @Test void explicitDraftRunsOnceThroughGovernanceWithoutModelCalls(){run("trainer");}
    @Test void explicitDraftCannotBypassRoleDenial(){run("unprivileged");}
}
