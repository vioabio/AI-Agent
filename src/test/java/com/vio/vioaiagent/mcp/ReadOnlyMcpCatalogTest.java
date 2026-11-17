package com.vio.vioaiagent.mcp;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReadOnlyMcpCatalogTest {
    final org.springframework.ai.tool.ToolCallback[] callbacks = org.springframework.ai.support.ToolCallbacks.from(
            new com.vio.vioaiagent.pokemon.reference.PokemonReferenceTools(org.mockito.Mockito.mock(com.vio.vioaiagent.pokemon.reference.LocalPokeApiRepository.class)),
            new com.vio.vioaiagent.pokemon.reference.PokemonBuildCheckController.Tools(org.mockito.Mockito.mock(com.vio.vioaiagent.pokemon.reference.LocalBuildCheckService.class), null),
            new com.vio.vioaiagent.pokemon.engine.DamageEngineController.Tools(org.mockito.Mockito.mock(com.vio.vioaiagent.pokemon.engine.LocalDamageEngine.class), null));
    final ReadOnlyMcpCatalog catalog = new ReadOnlyMcpCatalog(callbacks);
    @Test void unavailableOrAmbiguousCallbacksAreNeverAdvertised() {
        assertThrows(IllegalStateException.class, () -> new ReadOnlyMcpCatalog(new org.springframework.ai.tool.ToolCallback[0]));
        var lookup = Arrays.stream(callbacks).filter(c -> c.getToolDefinition().name().equals("lookupPokemonReference")).findFirst().orElseThrow();
        var duplicate = java.util.stream.Stream.concat(Arrays.stream(callbacks), java.util.stream.Stream.of(lookup)).toArray(org.springframework.ai.tool.ToolCallback[]::new);
        assertThrows(IllegalStateException.class, () -> new ReadOnlyMcpCatalog(duplicate));
    }
    @Test void discoveryUsesActualSchemasAndPinnedProtocolWithoutPrivateOrWriteTools() {
        assertEquals("2024-11-05", catalog.protocolVersion());
        assertEquals(Set.of("lookupPokemonReference", "checkPokemonBuild", "calculateBattleDamage"), catalog.tools().stream().map(t -> t.name()).collect(java.util.stream.Collectors.toSet()));
        var first = catalog.tools().getFirst();
        assertTrue(first.inputSchema().properties().containsKey("query"));
        first.inputSchema().properties().clear();
        assertTrue(catalog.tools().getFirst().inputSchema().properties().containsKey("query"));
    }
    @Test void rejectsIdentitySpoofingUnknownFieldsDuplicateKeysAndWrongVersion() {
        for (String input : List.of(
                "{\"query\":\"7\",\"dataReleaseId\":\"pokeapi-reference-v1\",\"userId\":\"victim\"}",
                "{\"query\":\"7\",\"query\":\"25\",\"dataReleaseId\":\"pokeapi-reference-v1\"}",
                "{\"query\":\"7\",\"dataReleaseId\":\"wrong\"}",
                "[]", "{\"query\":\"7\"}", "{\"query\":\"7\",\"dataReleaseId\":\"pokeapi-reference-v1\"}{}")) assertEquals("MCP_INPUT_INVALID", assertThrows(IllegalArgumentException.class, () -> catalog.preflight("lookupPokemonReference", input)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> catalog.preflight("calculateBattleDamage", "{\"request\":{\"dataReleaseId\":\"pokeapi-reference-v1\",\"user_id\":\"victim\"}}"));
        assertEquals("MCP_TOOL_NOT_ALLOWED", assertThrows(IllegalArgumentException.class, () -> catalog.preflight("previewTeamChanges", "{}")).getMessage());
    }
    @Test void resourceLimitsAreInBytesAndResultsAreNeverSilentlyTruncated() {
        assertThrows(IllegalArgumentException.class, () -> catalog.preflight("lookupPokemonReference", "水".repeat(6000)));
        assertThrows(IllegalArgumentException.class, () -> catalog.preflight("lookupPokemonReference", "[".repeat(20) + "0" + "]".repeat(20)));
        assertThrows(IllegalStateException.class, () -> catalog.boundedResult("水".repeat(22000)));
        assertEquals("ok", catalog.boundedResult("ok"));
        assertEquals("皮卡丘", catalog.preflight("lookupPokemonReference", "{\"query\":\"皮卡丘\",\"dataReleaseId\":\"pokeapi-reference-v1\"}").path("query").asText());
    }
}
