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

class BuildCheckAgentAnswerTest {
    @TempDir Path directory;
    String run(String role) throws Exception {
        return run(role,false);
    }
    String run(String role,boolean malformedFirst) throws Exception {
        var f=new BuildCheckFixture(directory);var events=new ArrayList<ToolGovernanceEvent>();var masker=new ParameterMasker();var hitl=new HitlManager();
        var gateway=new ToolExecutionGateway(hitl,new PathGuard(List.of(directory.toString())),new CommandGuard(List.of()),masker,new AuditLogger(masker));
        var tools=gateway.wrap(ToolCallbacks.from(new PokemonBuildCheckController.Tools(f.service,null)));var model=mock(ChatModel.class);
        String input="{\"request\":"+f.json.valueToTree(f.request())+"}";
        var responses=new ArrayDeque<ChatResponse>();
        if(malformedFirst)responses.add(new ChatResponse(List.of(new Generation(new AssistantMessage("",Map.of(),List.of(new AssistantMessage.ToolCall("broken","function","checkPokemonBuild",input.substring(0,input.length()-1))))))));
        responses.add(new ChatResponse(List.of(new Generation(new AssistantMessage("",Map.of(),List.of(new AssistantMessage.ToolCall("check","function","checkPokemonBuild",input)))))));
        responses.add(new ChatResponse(List.of(new Generation(new AssistantMessage("错误说明：全部合法并且已保存队伍")))));
        when(model.call(any(Prompt.class))).thenAnswer(invocation->{
            Prompt prompt=invocation.getArgument(0);
            for(var message:prompt.getInstructions())if(message instanceof AssistantMessage assistant)for(var call:assistant.getToolCalls())assertTrue(f.json.readTree(call.arguments()).isObject(),"Malformed arguments must not be replayed");
            return responses.remove();
        });
        ToolExecutionContextHolder.set(new ToolExecutionContext("owner","run-build-agent",Set.of(role),"trace",null,events::add));
        try {
            var agent=new VioManus(tools,null,model);agent.setToolExecutionGateway(gateway);agent.setCurrentUserRequest("请检查这个成员的配招");var deltas=new ArrayList<String>();agent.setAnswerDeltaSink(deltas::add);agent.run("请检查这个成员的配招");
            assertEquals(agent.getFinalAnswer(),String.join("",deltas));assertFalse(agent.getFinalAnswer().contains("全部合法"));assertFalse(agent.getFinalAnswer().contains("已保存队伍"));
            if(role.equals("trainer")){var finish=events.stream().filter(e->e.eventType().equals("tool.finished")).findFirst().orElseThrow();assertEquals("VERIFIED",((Map<?,?>)finish.attributes().get("outputEvidence")).get("status"));assertTrue(finish.attributes().toString().contains("reportSha256"));}
            else{assertEquals(0,f.networkCalls.get());assertFalse(events.stream().anyMatch(e->e.eventType().equals("tool.started")));}
            return agent.getFinalAnswer();
        }finally{ToolExecutionContextHolder.clear();gateway.shutdown();hitl.shutdown();}
    }
    @Test void governedReadOnlyCheckPublishesBoundFourMoveCoverageInsteadOfModelLegalOrSaveClaims() throws Exception {String answer=run("trainer");assertTrue(answer.contains("组合仍待审核"));assertTrue(answer.contains("没有修改或保存队伍"));assertTrue(answer.contains("招式学习器：快照记录此途径"));assertFalse(answer.contains("0级即可学习"));assertFalse(answer.contains("reportSha256"));}
    @Test void deniedRoleCannotUseTheCheckerOrPublishInventedResults() throws Exception {assertTrue(run("unprivileged").contains("未取得与原配置绑定"));}
    @Test void malformedArgumentsAreDeniedAndDoNotPoisonTheNextModelRequest() throws Exception {assertTrue(run("trainer",true).contains("组合仍待审核"));}
}
