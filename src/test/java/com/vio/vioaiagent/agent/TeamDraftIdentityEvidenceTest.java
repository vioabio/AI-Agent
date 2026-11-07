package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.reference.*;
import com.vio.vioaiagent.security.*;
import com.vio.vioaiagent.tools.governance.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TeamDraftIdentityEvidenceTest {
    @TempDir Path directory;
    final ObjectMapper json = new ObjectMapper();
    final String question = "请核对整队配置：第1位杰尼龟，第2位雪妖女。所有培养字段都还没填写，请保持未填写，不要代填或保存。";

    void resolve(TeamDraftIdentityEvidence gate, String name, long id, String data) throws Exception {
        String args = json.writeValueAsString(Map.of("query", name, "dataReleaseId", "pokeapi-reference-v1"));
        String output = json.writeValueAsString(Map.of("fact", Map.of("formId", id, "speciesNameZh", name,
                "dataReleaseId", data, "gameReleaseId", "pokemon-scarlet-violet")));
        gate.observe("lookupPokemonReference", output, args);
    }

    String arguments(long second) {
        return TeamBuildRequestRoute.arguments(new TeamBuildCheckFacts.Request("pokeapi-reference-v1", "pokemon-scarlet-violet",
                List.of(new TeamBuildCheckFacts.Member(1, 7, null), new TeamBuildCheckFacts.Member(2, second, null))));
    }

    @Test void allMembersMustResolveBeforeAggregationAndWrongIdCannotExecute() throws Exception {
        var gate = new TeamDraftIdentityEvidence(question);
        assertFalse(gate.ready()); assertThrows(IllegalArgumentException.class, () -> gate.verify("checkPokemonTeamBuild", arguments(478)));
        resolve(gate, "杰尼龟", 7, "pokeapi-reference-v1"); assertFalse(gate.ready());
        resolve(gate, "雪妖女", 478, "pokeapi-reference-v1"); assertTrue(gate.ready());
        assertTrue(gate.instruction().contains("雪妖女=478"));
        assertFalse(gate.instruction().contains("待核对名称"));
        assertThrows(IllegalArgumentException.class, () -> gate.verify("checkPokemonTeamBuild", arguments(872)));
        assertDoesNotThrow(() -> gate.verify("checkPokemonTeamBuild", arguments(478)));
    }

    @Test void wrongNamesVersionsAndFailedLookupsCannotBind() throws Exception {
        var gate = new TeamDraftIdentityEvidence(question);
        resolve(gate, "杰尼龟", 7, "pokeapi-reference-v1");
        resolve(gate, "雪妖女", 478, "other-release"); assertFalse(gate.ready());
        gate.observe("lookupPokemonReference", "{\"fact\":{\"formId\":872,\"speciesNameZh\":\"雪吞虫\",\"dataReleaseId\":\"pokeapi-reference-v1\",\"gameReleaseId\":\"pokemon-scarlet-violet\"}}",
                "{\"query\":\"雪妖女\",\"dataReleaseId\":\"pokeapi-reference-v1\"}"); assertFalse(gate.ready());
        resolve(gate, "雪妖女", 478, "pokeapi-reference-v1"); assertTrue(gate.ready());
        gate.observe("lookupPokemonReference", "[tool.error] unavailable", "{\"query\":\"雪妖女\",\"dataReleaseId\":\"pokeapi-reference-v1\"}");
        assertFalse(gate.ready());
    }

    @Test void explicitNumericNameMustEqualActualFormIdRatherThanModelGuess() throws Exception {
        var gate = new TeamDraftIdentityEvidence("请核对整队配置：第1位478。所有培养配置均未填写。");
        gate.observe("lookupPokemonReference", "{\"fact\":{\"formId\":478,\"speciesNameZh\":\"雪妖女\",\"dataReleaseId\":\"pokeapi-reference-v1\",\"gameReleaseId\":\"pokemon-scarlet-violet\"}}",
                "{\"query\":\"478\",\"dataReleaseId\":\"pokeapi-reference-v1\"}");
        assertTrue(gate.ready());
    }

    @Test void groupedSlotsAndSparseOrderUseLiteralNamesWithoutSpeciesTable() throws Exception {
        var plan = json.readTree(Path.of("evaluation/campus/team-natural-v1/cases.json").toFile());
        for (var item : plan.path("cases")) {
            var gate = new TeamDraftIdentityEvidence(item.path("prompt").asText());
            resolve(gate, "杰尼龟", 7, "pokeapi-reference-v1"); resolve(gate, "雪妖女", 478, "pokeapi-reference-v1");
            assertTrue(gate.ready(), item.path("id").asText());
            gate.verify("checkPokemonTeamBuild", json.writeValueAsString(Map.of("request", item.path("expectedRequest"))));
        }
        var different = new TeamDraftIdentityEvidence(question.replace("杰尼龟", "伊布").replace("雪妖女", "pikachu"));
        resolve(different, "伊布", 133, "pokeapi-reference-v1"); resolve(different, "pikachu", 25, "pokeapi-reference-v1");
        assertTrue(different.ready());
    }

    @Test void unsupportedGrammarClarifiesAndSavedStructuredRequestsKeepExistingRoute() {
        var unsupported = new TeamDraftIdentityEvidence(question.replace("第1位杰尼龟", "第1位可能是杰尼龟或皮卡丘"));
        assertFalse(unsupported.ready()); assertThrows(IllegalArgumentException.class, () -> unsupported.verify("checkPokemonTeamBuild", arguments(478)));
        for (String q : List.of("请核对整队配置，先读取我的队伍。", "请检查整队配置。\n{}", question + "另外列出之前的队伍。")) {
            var gate = new TeamDraftIdentityEvidence(q);
            assertDoesNotThrow(() -> gate.verify("checkPokemonTeamBuild", arguments(478)));
        }
    }

    @Test void noSubsetDuplicateSlotVersionSwapOrOmittedBuildCanPass() throws Exception {
        var gate = new TeamDraftIdentityEvidence(question);
        resolve(gate, "杰尼龟", 7, "pokeapi-reference-v1"); resolve(gate, "雪妖女", 478, "pokeapi-reference-v1");
        for (String bad : List.of(arguments(478).replace("pokeapi-reference-v1", "other"),
                arguments(478).replace("\"slot\":2", "\"slot\":1"), arguments(478).replace(",\"build\":null", "")))
            assertThrows(IllegalArgumentException.class, () -> gate.verify("checkPokemonTeamBuild", bad));
        var subset = json.readTree(arguments(478)); ((com.fasterxml.jackson.databind.node.ArrayNode) subset.path("request").path("members")).remove(1);
        assertThrows(IllegalArgumentException.class, () -> gate.verify("checkPokemonTeamBuild", subset.toString()));
    }

    @Test void toolAvailabilityChangesOnlyAfterAllReferenceResultsAndDoesNotLeakAcrossRuns() throws Exception {
        var gate = new TeamDraftIdentityEvidence(question);
        var aggregate = new ToolCallback() {
            public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("checkPokemonTeamBuild").description("test").inputSchema("{}").build(); }
            public String call(String input) { throw new AssertionError("not called"); }
        };
        var tools = new ToolCallback[]{aggregate}; assertEquals(0, gate.planningTools(tools).length);
        resolve(gate, "杰尼龟", 7, "pokeapi-reference-v1"); resolve(gate, "雪妖女", 478, "pokeapi-reference-v1");
        assertSame(tools, gate.planningTools(tools));
        assertEquals(0, new TeamDraftIdentityEvidence(question).planningTools(tools).length);
    }

    @Test void gatewayRejectsBeforeCallbackAndRecordsRejectionWhileValidCallUsesExistingGateway() throws Exception {
        var f = new BuildCheckFixture(directory);
        var gate = new TeamDraftIdentityEvidence("请核对整队配置：第1位杰尼龟。所有培养配置均未填写。");
        var calls = new AtomicInteger(); var events = new ArrayList<ToolGovernanceEvent>();
        var masker = new ParameterMasker();
        var gateway = new ToolExecutionGateway(new HitlManager(), new PathGuard(List.of(directory.toString())), new CommandGuard(List.of()), masker, new AuditLogger(masker));
        var request = new TeamBuildCheckFacts.Request("pokeapi-reference-v1", "pokemon-scarlet-violet", List.of(new TeamBuildCheckFacts.Member(1, 7, null)));
        String input = TeamBuildRequestRoute.arguments(request);
        String report = f.json.writeValueAsString(PokemonTeamBuildCheckController.checkMembers(f.service, request));
        var tool = new ToolCallback() {
            public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("checkPokemonTeamBuild").description("test").inputSchema("{}").build(); }
            public String call(String raw) { calls.incrementAndGet(); return report; }
        };
        gateway.wrap(tool);
        var context = new ToolExecutionContext("u", "identity-test", Set.of("trainer"), "trace", null, events::add).withInputConstraint(gate::verify);
        try {
            assertEquals(ToolGovernanceResult.Status.REJECTED, gateway.execute(tool, input, context).status());
            assertEquals(0, calls.get()); assertTrue(events.stream().anyMatch(e -> "tool.rejected".equals(e.eventType())));
            gate.observe("lookupPokemonReference", f.json.writeValueAsString(f.repository.detail("7", "pokeapi-reference-v1")), "{\"query\":\"杰尼龟\",\"dataReleaseId\":\"pokeapi-reference-v1\"}");
            assertEquals(ToolGovernanceResult.Status.EXECUTED, gateway.execute(tool, input, context).status()); assertEquals(1, calls.get());
            assertNotNull(context.withEventSink(events::add).withIdempotencyKey("k").withTokenBinding(null).withPrincipalPolicy(null).inputConstraint());
            assertNull(ToolExecutionContextHolder.peek());
        } finally { gateway.shutdown(); }
    }

    @Test void agentActCarriesConstraintObservesActualReferenceAndRestoresParentContext() throws Exception {
        var f = new BuildCheckFixture(directory);
        String q = "请核对整队配置：第1位杰尼龟。所有培养配置均未填写。";
        String refArgs = "{\"query\":\"杰尼龟\",\"dataReleaseId\":\"pokeapi-reference-v1\"}";
        var request = new TeamBuildCheckFacts.Request("pokeapi-reference-v1", "pokemon-scarlet-violet", List.of(new TeamBuildCheckFacts.Member(1, 7, null)));
        String good = TeamBuildRequestRoute.arguments(request), wrong = good.replace("\"pokemonId\":7", "\"pokemonId\":872");
        var model = org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
        var ref = new org.springframework.ai.chat.messages.AssistantMessage("", Map.of(), List.of(
                new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("ref", "function", "lookupPokemonReference", refArgs)));
        var bad = new org.springframework.ai.chat.messages.AssistantMessage("", Map.of(), List.of(
                new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("bad", "function", "checkPokemonTeamBuild", wrong)));
        var valid = new org.springframework.ai.chat.messages.AssistantMessage("", Map.of(), List.of(
                new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("valid", "function", "checkPokemonTeamBuild", good)));
        org.mockito.Mockito.when(model.call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(ref))),
                        new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(bad))),
                        new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(valid))));
        var manager = org.mockito.Mockito.mock(org.springframework.ai.model.tool.ToolCallingManager.class);
        var parent = new ToolExecutionContext("owner", "act-test", Set.of("trainer"), "trace", null, null);
        var seen = new AtomicInteger();
        org.mockito.Mockito.when(manager.executeToolCalls(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            var context = ToolExecutionContextHolder.peek(); assertNotNull(context.inputConstraint());
            var response = (org.springframework.ai.chat.model.ChatResponse) invocation.getArgument(1);
            var assistant = response.getResult().getOutput(); var call = assistant.getToolCalls().getFirst();
            String output;
            if (seen.getAndIncrement() == 0) {
                assertThrows(IllegalArgumentException.class, () -> context.inputConstraint().accept("checkPokemonTeamBuild", good));
                output = f.json.writeValueAsString(f.repository.detail("7", "pokeapi-reference-v1"));
            } else if ("bad".equals(call.id())) {
                assertThrows(IllegalArgumentException.class, () -> context.inputConstraint().accept(call.name(), call.arguments()));
                output = "[tool.denied] wrong member identity";
            } else {
                assertDoesNotThrow(() -> context.inputConstraint().accept(call.name(), call.arguments()));
                output = f.json.writeValueAsString(PokemonTeamBuildCheckController.checkMembers(f.service, request));
            }
            return new org.springframework.ai.model.tool.DefaultToolExecutionResult(List.of(assistant,
                    new org.springframework.ai.chat.messages.ToolResponseMessage(List.of(
                            new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(call.id(), call.name(), output)))), false);
        });
        var agent = new ToolCallAgent(new ToolCallback[0]);
        agent.setSystemPrompt("verified facts"); agent.setCurrentUserRequest(q);
        agent.setChatClient(org.springframework.ai.chat.client.ChatClient.builder(model).build()); agent.setToolCallingManager(manager);
        ToolExecutionContextHolder.set(parent);
        try {
            for (int i = 0; i < 3; i++) { assertTrue(agent.think()); agent.act(); assertSame(parent, ToolExecutionContextHolder.peek()); }
            assertEquals(3, seen.get());
        } finally { ToolExecutionContextHolder.clear(); }
    }
}
