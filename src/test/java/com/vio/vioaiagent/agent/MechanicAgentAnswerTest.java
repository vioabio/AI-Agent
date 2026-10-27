package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.reference.*;
import com.vio.vioaiagent.security.*;
import com.vio.vioaiagent.tools.governance.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MechanicAgentAnswerTest {
    @TempDir Path directory;
    String run(String role) throws Exception {
        var masker = new ParameterMasker(); var hitl = new HitlManager();
        var gateway = new ToolExecutionGateway(hitl, new PathGuard(List.of(directory.toString())), new CommandGuard(List.of()), masker, new AuditLogger(masker));
        var catalog = spy(new IndependentMechanicCatalog(new ObjectMapper(), new DefaultResourceLoader())); var events = new ArrayList<ToolGovernanceEvent>();
        var tools = gateway.wrap(ToolCallbacks.from(new PokemonMechanicsController.Tools(catalog, null)));
        var model = mock(ChatModel.class); when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage("", Map.of(), List.of(new AssistantMessage.ToolCall("mechanics", "function", "lookupPokemonMechanics", MechanicAnswerEvidenceTest.INPUT)))))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("错误模型机制：血量低于一半所有招式固定翻倍")))));
        ToolExecutionContextHolder.set(new ToolExecutionContext("owner", "run-mechanic-agent", Set.of(role), "trace", null, events::add));
        try {
            var agent = new VioManus(tools, null, model); agent.setToolExecutionGateway(gateway); agent.setCurrentUserRequest("请核对激流的触发门槛和倍率");
            var deltas = new ArrayList<String>(); agent.setAnswerDeltaSink(deltas::add); agent.run("请核对激流的触发门槛和倍率");
            assertEquals(agent.getFinalAnswer(), String.join("", deltas)); assertFalse(agent.getFinalAnswer().contains("固定翻倍"));
            if (role.equals("trainer")) {
                verify(catalog, times(1)).read("ability", "torrent", "pokeapi-reference-v1", "pokemon-scarlet-violet");
                var finished = events.stream().filter(e -> e.eventType().equals("tool.finished")).findFirst().orElseThrow().attributes();
                assertEquals("VERIFIED", ((Map<?, ?>) finished.get("outputEvidence")).get("status"));
                assertTrue(events.stream().anyMatch(e -> e.eventType().equals("tool.started") && e.attributes().toString().contains("tool-input-evidence-v1")));
            } else { verify(catalog, never()).read(anyString(), anyString(), anyString(), anyString()); assertFalse(events.stream().anyMatch(e -> e.eventType().equals("tool.started"))); }
            return agent.getFinalAnswer();
        } finally { ToolExecutionContextHolder.clear(); gateway.shutdown(); hitl.shutdown(); }
    }
    @Test void actualToolProjectionReplacesInventedModelMechanismsInStreamingOutput() throws Exception { assertTrue(run("trainer").contains("进攻能力乘以1.5")); }
    @Test void deniedRoleCannotReadPinnedMechanicsOrFallBackToModelClaims() throws Exception { assertTrue(run("unprivileged").contains("未取得可核验")); }
}
