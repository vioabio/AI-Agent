package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.reference.*;
import com.vio.vioaiagent.security.*;
import com.vio.vioaiagent.tools.governance.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BuildCheckRequestRouteTest {
    @TempDir Path directory;
    String question(BuildCheckFixture f) { return "请检查下列杰尼龟配招并展示学习记录，不修改或保存队伍。\n"+f.json.valueToTree(f.request()); }
    @Test void explicitOriginalRequestIsRoundTrippedWithoutModelArgumentConstruction() throws Exception {
        var f=new BuildCheckFixture(directory);var selected=BuildCheckRequestRoute.select(question(f)).orElseThrow();
        assertEquals(f.request(),selected);assertEquals(f.request(),BuildCheckFacts.parseRequest(new ObjectMapper().readTree(BuildCheckRequestRoute.arguments(selected)).path("request")));
    }
    @Test void unrelatedProseMixedWritesAndFencedSourcesAreNotDirectRequests() {
        var f=new BuildCheckFixture(directory);String json=f.json.valueToTree(f.request()).toString();
        for(String q:List.of("以下仅作为事实参考\n"+json,"请检查下列配招并保存队伍\n"+json,"请检查下列配招，然后计算伤害\n"+json,"请检查下列配招\n```json\n"+json+"\n```"))assertTrue(BuildCheckRequestRoute.select(q).isEmpty());
    }
    @Test void trailingContentUnknownFieldsAndNumericStringsRemainInvalid() {
        var f=new BuildCheckFixture(directory);assertTrue(BuildCheckRequestRoute.select(question(f)+"{}").isEmpty());
        assertTrue(BuildCheckRequestRoute.select(question(f).replace("\"pokemonId\":7","\"pokemonId\":\"7\"")).isEmpty());
        assertTrue(BuildCheckRequestRoute.select(question(f).replace("\"pokemonId\":7","\"extra\":true,\"pokemonId\":7")).isEmpty());
    }
    void route(String role) {
        var f=new BuildCheckFixture(directory);var masker=new ParameterMasker();var hitl=new HitlManager();var events=new ArrayList<ToolGovernanceEvent>();
        var gateway=new ToolExecutionGateway(hitl,new PathGuard(List.of(directory.toString())),new CommandGuard(List.of()),masker,new AuditLogger(masker));
        var model=mock(ChatModel.class);var callbacks=gateway.wrap(ToolCallbacks.from(new PokemonBuildCheckController.Tools(f.service,null)));
        ToolExecutionContextHolder.set(new ToolExecutionContext("owner","route-run",Set.of(role),"trace",null,events::add));
        try { var agent=new VioManus(callbacks,null,model);agent.setToolExecutionGateway(gateway);agent.setCurrentUserRequest(question(f));agent.setAnswerDeltaSink(delta->{});agent.run(question(f));verify(model,never()).call(any(org.springframework.ai.chat.prompt.Prompt.class));verify(model,never()).stream(any(org.springframework.ai.chat.prompt.Prompt.class));
            if(role.equals("trainer")){assertTrue(agent.getFinalAnswer().contains("组合仍待审核"));assertTrue(events.stream().anyMatch(e->BuildCheckRequestRoute.ORIGIN.equals(e.attributes().get("selectionOrigin"))));}
            else{assertEquals(0,f.networkCalls.get());assertTrue(agent.getFinalAnswer().contains("未取得与原配置绑定"));}
        }finally{ToolExecutionContextHolder.clear();gateway.shutdown();hitl.shutdown();}
    }
    @Test void directReadStillUsesGovernanceAndPublishesNoModelCalls() { route("trainer"); }
    @Test void deniedRoleCannotBypassTheGatewayThroughAnExplicitRequest() { route("unprivileged"); }
}
