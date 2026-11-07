package com.vio.vioaiagent.pokemon.reference;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.vio.vioaiagent.run.*;
import com.vio.vioaiagent.tools.governance.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LocalLearnsetReferencesTest {
    @TempDir Path directory;
    final ObjectMapper json = new ObjectMapper();
    ObjectNode source() throws Exception {
        return (ObjectNode) json.readTree("""
            {"id":7,"name":"squirtle","moves":[{"move":{"name":"water-pulse"},"version_group_details":[
              {"version_group":{"name":"sword-shield"},"move_learn_method":{"name":"level-up"},"level_learned_at":12},
              {"version_group":{"name":"scarlet-violet"},"move_learn_method":{"name":"level-up"},"level_learned_at":15,"order":null},
              {"version_group":{"name":"scarlet-violet"},"move_learn_method":{"name":"machine"},"level_learned_at":0,"order":null}]}]}
            """);
    }
    PokeApiClient.Snapshot snapshot(ObjectNode body) { return new PokeApiClient.Snapshot(body.toString(), PokeApiClient.digest(body.toString()), Instant.parse("2026-10-26T00:00:00Z"), "https://pokeapi.co/api/v2/pokemon/7/"); }
    LocalLearnsetReferences adapter() { return new LocalLearnsetReferences(json, new PokeApiClient(json, directory, url -> { throw new AssertionError("Unexpected transport"); })); }
    ArrayNode details(ObjectNode body) { return (ArrayNode) body.path("moves").get(0).path("version_group_details"); }
    @Test void preservesBothSvMethodsLevelZeroAndNullOrderWithoutBorrowingOlderLevel() throws Exception {
        var fact = adapter().project(7, "water-pulse", snapshot(source()));
        assertEquals(List.of(new PokemonLearnsetFacts.Entry("level-up", 15, null), new PokemonLearnsetFacts.Entry("machine", 0, null)), fact.entries());
        assertEquals("RECORDED", fact.status()); assertEquals(fact, PokemonLearnsetFacts.validate(json.valueToTree(fact)));
    }
    @Test void olderVersionOnlyAndAbsentMoveAreRecordedAsMissingRatherThanUnlearnable() throws Exception {
        var raw = source(); details(raw).remove(2); details(raw).remove(1);
        for (String move : List.of("water-pulse", "absent-move")) {
            var fact = adapter().project(7, move, snapshot(raw)); assertTrue(fact.entries().isEmpty()); assertEquals("NOT_RECORDED_IN_SNAPSHOT", fact.status());
        }
    }
    @Test void deduplicatesIdenticalRowsButKeepsDistinctLevelsAndUnknownMethodNames() throws Exception {
        var raw = source(); var rows = details(raw); rows.add(rows.get(1).deepCopy());
        var unknown = rows.get(1).deepCopy(); ((ObjectNode) unknown.path("move_learn_method")).put("name", "future-method"); rows.add(unknown);
        assertEquals(3, adapter().project(7, "water-pulse", snapshot(raw)).entries().size());
    }
    @Test void malformedSelectedRowsNeverBecomeAnEmptySuccessfulResult() throws Exception {
        for (String field : List.of("level_learned_at", "move_learn_method", "version_group")) {
            var raw = source(); ((ObjectNode) details(raw).get(1)).remove(field);
            assertThrows(IllegalStateException.class, () -> adapter().project(7, "water-pulse", snapshot(raw)), field);
        }
        var raw = source(); ((ObjectNode) details(raw).get(1)).put("level_learned_at", "15");
        assertThrows(IllegalStateException.class, () -> adapter().project(7, "water-pulse", snapshot(raw)));
    }
    @Test void mismatchedIdentityOriginAndDigestFailClosed() throws Exception {
        var raw = source(); var valid = snapshot(raw);
        assertThrows(IllegalStateException.class, () -> adapter().project(8, "water-pulse", valid));
        assertThrows(IllegalStateException.class, () -> adapter().project(7, "water-pulse", new PokeApiClient.Snapshot(valid.payload(), "sha256:" + "0".repeat(64), valid.retrievedAt(), valid.url())));
        assertThrows(IllegalStateException.class, () -> adapter().project(7, "water-pulse", new PokeApiClient.Snapshot(valid.payload(), valid.sha256(), valid.retrievedAt(), "https://example.com/pokemon/7/")));
    }
    @Test void ambiguousMoveBlocksAndOversizedSelectedMethodListsFail() throws Exception {
        var raw = source(); ((ArrayNode) raw.path("moves")).add(raw.path("moves").get(0).deepCopy());
        assertThrows(IllegalStateException.class, () -> adapter().project(7, "water-pulse", snapshot(raw)));
        var large = source(); for (int i = 1; i < 34; i++) { var row = details(large).get(1).deepCopy(); ((ObjectNode) row).put("level_learned_at", i); details(large).add(row); }
        assertThrows(IllegalStateException.class, () -> adapter().project(7, "water-pulse", snapshot(large)));
    }
    @Test void invalidInputsAreRejectedBeforeNetworkAndExistingCacheWorksAfterRestart() throws Exception {
        var calls = new AtomicInteger(); String body = source().toString();
        var refs = new LocalLearnsetReferences(json, new PokeApiClient(json, directory, url -> { calls.incrementAndGet(); return body; }));
        assertThrows(IllegalArgumentException.class, () -> refs.read(0, "water-pulse", "pokeapi-reference-v1", "pokemon-scarlet-violet"));
        assertThrows(IllegalArgumentException.class, () -> refs.read(7, "../water-pulse", "pokeapi-reference-v1", "pokemon-scarlet-violet"));
        assertThrows(IllegalArgumentException.class, () -> refs.read(7, "water-pulse", "other", "pokemon-scarlet-violet"));
        assertThrows(IllegalArgumentException.class, () -> refs.read(7, "water-pulse", "pokeapi-reference-v1", "pokemon-sword-shield"));
        assertEquals(0, calls.get()); var first = refs.read(7, "water-pulse", "pokeapi-reference-v1", "pokemon-scarlet-violet");
        assertEquals(first, adapter().read(7, "water-pulse", "pokeapi-reference-v1", "pokemon-scarlet-violet")); assertEquals(1, calls.get());
        ObjectNode cached = (ObjectNode) json.readTree(directory.resolve("pokemon-7.json").toFile()); cached.put("sha256", "bad"); Files.writeString(directory.resolve("pokemon-7.json"), cached.toString());
        assertThrows(IllegalStateException.class, () -> adapter().read(7, "water-pulse", "pokeapi-reference-v1", "pokemon-scarlet-violet"));
    }
    @Test void changedProjectionUnknownFieldsAndCoercedNumbersAreRejected() throws Exception {
        ObjectNode valid = json.valueToTree(adapter().project(7, "water-pulse", snapshot(source())));
        var changed = valid.deepCopy(); ((ObjectNode) changed.path("entries").get(0)).put("levelLearnedAt", 99);
        assertThrows(IllegalArgumentException.class, () -> PokemonLearnsetFacts.validate(changed));
        var numeric = valid.deepCopy(); numeric.put("pokemonId", "7"); assertThrows(IllegalArgumentException.class, () -> PokemonLearnsetFacts.validate(numeric));
        var extra = valid.deepCopy(); extra.put("legality", "guaranteed"); assertThrows(IllegalArgumentException.class, () -> PokemonLearnsetFacts.validate(extra));
        var unknown = valid.deepCopy(); ((ObjectNode) unknown.path("entries").get(0)).put("tmNumber", "invented"); assertThrows(IllegalArgumentException.class, () -> PokemonLearnsetFacts.validate(unknown));
    }
    @Test void toolUsesBothAuthenticatedRunVersionsAndOwnershipBeforeReading() throws Exception {
        var runs = new InMemoryRunRepository(); runs.save(AgentRun.create("run-learnset", "session", "conversation", "alice", "test", "pokemon-scarlet-violet", "casual-singles", "casual-v1", "zh-CN", null, "pokeapi-reference-v1", null, null, "v1", "trace", null, null));
        String body = source().toString(); var calls = new AtomicInteger();
        var refs = new LocalLearnsetReferences(json, new PokeApiClient(json, directory, url -> { calls.incrementAndGet(); return body; }));
        var tool = new PokemonLearnsetController.Tools(refs, new RunToolScope(runs));
        ToolExecutionContextHolder.set(new ToolExecutionContext("alice", "run-learnset", Set.of("trainer"), "trace", null, null));
        try {
            assertThrows(IllegalArgumentException.class, () -> tool.lookupPokemonLearnset(7, "water-pulse", "other", "pokemon-scarlet-violet"));
            assertThrows(IllegalArgumentException.class, () -> tool.lookupPokemonLearnset(7, "water-pulse", "pokeapi-reference-v1", "pokemon-sword-shield"));
            assertEquals(0, calls.get()); assertEquals("RECORDED", tool.lookupPokemonLearnset(7, "water-pulse", "pokeapi-reference-v1", "pokemon-scarlet-violet").status());
            ToolExecutionContextHolder.set(new ToolExecutionContext("bob", "run-learnset", Set.of("trainer"), "trace", null, null));
            assertThrows(IllegalStateException.class, () -> tool.lookupPokemonLearnset(7, "water-pulse", "pokeapi-reference-v1", "pokemon-scarlet-violet"));
        } finally { ToolExecutionContextHolder.clear(); }
        var callback = ToolCallbacks.from(tool)[0]; assertEquals("lookupPokemonLearnset", callback.getToolDefinition().name());
        assertEquals(List.of("trainer", "operator"), ToolCapabilityCatalog.find("lookupPokemonLearnset").orElseThrow().allowedRoles());
    }
    @Test void httpReturnsTypedMethodsAndRejectsBadVersionsWithoutRawPayloads() throws Exception {
        String body = source().toString(); var refs = new LocalLearnsetReferences(json, new PokeApiClient(json, directory, url -> body));
        var mvc = MockMvcBuilders.standaloneSetup(new PokemonLearnsetController(refs)).build();
        mvc.perform(get("/pokemon/reference/learnsets/7/water-pulse").param("dataReleaseId", "pokeapi-reference-v1").param("gameReleaseId", "pokemon-scarlet-violet"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.entries[0].levelLearnedAt").value(15)).andExpect(jsonPath("$.entries[1].methodKey").value("machine"));
        mvc.perform(get("/pokemon/reference/learnsets/7/water-pulse").param("dataReleaseId", "other").param("gameReleaseId", "pokemon-scarlet-violet"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("学习途径查询参数无效"));
    }
}
