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
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LearnsetAgentAnswerTest {
    @TempDir Path directory;
    String run(String role, boolean available) throws Exception {
        var masker = new ParameterMasker(); var hitl = new HitlManager();
        var gateway = new ToolExecutionGateway(hitl, new PathGuard(List.of(directory.toString())), new CommandGuard(List.of()), masker, new AuditLogger(masker));
        var events = new ArrayList<ToolGovernanceEvent>(); var calls = new java.util.concurrent.atomic.AtomicInteger();
        var json = new ObjectMapper(); String source = "{\"id\":7,\"name\":\"squirtle\",\"moves\":[{\"move\":{\"name\":\"water-pulse\"},\"version_group_details\":[{\"version_group\":{\"name\":\"scarlet-violet\"},\"move_learn_method\":{\"name\":\"level-up\"},\"level_learned_at\":15,\"order\":null}]}]}";
        var refs = new LocalLearnsetReferences(json, new PokeApiClient(json, directory.resolve("public"), url -> { calls.incrementAndGet(); if (!available) throw new IllegalStateException("private-network-fixture"); return source; }));
        var tools = gateway.wrap(ToolCallbacks.from(new PokemonLearnsetController.Tools(refs, null)));
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("", Map.of(), List.of(new AssistantMessage.ToolCall("learn", "function", "lookupPokemonLearnset", LearnsetAnswerEvidenceTest.INPUT)))))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("错误模型说明：99级学会，使用TM999")))));
        ToolExecutionContextHolder.set(new ToolExecutionContext("owner", "run-learn-agent", Set.of(role), "trace", null, events::add));
        try {
            var agent = new VioManus(tools, null, model); agent.setToolExecutionGateway(gateway); agent.setCurrentUserRequest("请查询杰尼龟water-pulse的学习途径");
            var deltas = new ArrayList<String>(); agent.setAnswerDeltaSink(deltas::add); agent.run("请查询杰尼龟water-pulse的学习途径");
            assertEquals(agent.getFinalAnswer(), String.join("", deltas)); assertFalse(agent.getFinalAnswer().contains("TM999")); assertFalse(agent.getFinalAnswer().contains("private-network-fixture"));
            if (role.equals("unprivileged")) { assertEquals(0, calls.get()); assertFalse(events.stream().anyMatch(e -> e.eventType().equals("tool.started"))); }
            else if (available) { assertEquals(1, calls.get()); assertTrue(events.stream().anyMatch(e -> e.eventType().equals("tool.finished") && "SUCCEEDED".equals(e.attributes().get("status"))));
                var input = events.stream().filter(e -> e.eventType().equals("tool.started")).findFirst().orElseThrow().attributes(); assertTrue(input.toString().contains("tool-input-evidence-v1"));
                var result = events.stream().filter(e -> e.eventType().equals("tool.finished")).findFirst().orElseThrow().attributes();
                assertEquals("VERIFIED", ((Map<?, ?>) result.get("outputEvidence")).get("status")); }
            return agent.getFinalAnswer();
        } finally { ToolExecutionContextHolder.clear(); gateway.shutdown(); hitl.shutdown(); }
    }
    @Test void actualGovernedToolResultReplacesInventedModelLearningTextInStreamingAnswer() throws Exception {
        assertTrue(run("trainer", true).contains("记录等级：15"));
    }
    @Test void deniedRoleAndFailedReadCannotBecomeModelInventedLearningFacts() throws Exception {
        assertTrue(run("unprivileged", true).contains("未取得可核验")); assertTrue(run("trainer", false).contains("未取得可核验"));
    }
}
