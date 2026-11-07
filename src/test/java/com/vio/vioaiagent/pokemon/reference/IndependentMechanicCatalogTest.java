package com.vio.vioaiagent.pokemon.reference;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vio.vioaiagent.run.*;
import com.vio.vioaiagent.tools.governance.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class IndependentMechanicCatalogTest {
    final ObjectMapper json = new ObjectMapper();
    IndependentMechanicCatalog catalog() { return new IndependentMechanicCatalog(json, new DefaultResourceLoader()); }
    ObjectNode read(IndependentMechanicCatalog c, String kind, String slug) { return c.read(kind, slug, "pokeapi-reference-v1", "pokemon-scarlet-violet"); }
    @Test void allSixOfflineNotesBindTheDescriptionLineCommitAndProjectParaphrase() throws Exception {
        var c = catalog(); var rows = json.readTree(new ClassPathResource("pokemon-mechanics-independent/catalog-v1.json").getInputStream()).path("records");
        assertEquals(6, rows.size());
        for (var row : rows) { assertEquals(row, read(c, row.path("kind").asText(), row.path("slug").asText())); assertEquals(row, IndependentMechanicCatalog.verifyCurrent(row)); }
        assertTrue(read(c, "ability", "torrent").path("explanationZh").asText().contains("进攻能力乘以1.5"));
        assertTrue(read(c, "ability", "rain-dish").path("explanationZh").asText().contains("万能伞"));
        assertTrue(read(c, "ability", "snow-cloak").path("descriptionEn").asText().contains("Snow"));
        assertFalse(read(c, "ability", "snow-cloak").path("descriptionEn").asText().contains("Hail"));
    }
    @Test void wrongKindsVersionsAndUnknownResourcesCannotFallBackToGenericOrOlderText() {
        var c = catalog(); assertThrows(IllegalArgumentException.class, () -> c.read("ability", "torrent", "other", "pokemon-scarlet-violet"));
        assertThrows(IllegalArgumentException.class, () -> c.read("ability", "torrent", "pokeapi-reference-v1", "pokemon-sword-shield"));
        assertThrows(IllegalArgumentException.class, () -> read(c, "nature", "timid")); assertThrows(IllegalArgumentException.class, () -> read(c, "move", "../protect"));
        assertThrows(com.vio.vioaiagent.pokemon.service.PokemonResourceNotFoundException.class, () -> read(c, "move", "protect"));
    }
    @Test void catalogLicenseExcerptAndGenerationCorruptionPreventStartup() {
        for (String file : List.of("catalog-v1.json", "metadata-v1.json", "LICENSE.showdown", "excerpts-v1.json", "generation-v1.txt")) {
            var loader = new DefaultResourceLoader() { @Override public Resource getResource(String location) {
                return location.endsWith("/" + file) ? new ByteArrayResource("tampered-source".getBytes(StandardCharsets.UTF_8)) : super.getResource(location);
            }};
            assertThrows(IllegalStateException.class, () -> new IndependentMechanicCatalog(json, loader), file);
        }
    }
    @Test void selfConsistentForgedParaphraseStillFailsCurrentSourceBinding() throws Exception {
        var note = read(catalog(), "ability", "torrent"); note.put("explanationZh", "虚构：所有伤害固定翻倍");
        var sorted = new TreeMap<String, JsonNode>(); note.fields().forEachRemaining(e -> { if (!e.getKey().equals("projectionSha256")) sorted.put(e.getKey(), e.getValue()); });
        note.put("projectionSha256", PokeApiClient.digest(json.writeValueAsString(sorted)));
        assertDoesNotThrow(() -> IndependentMechanicFacts.validate(note)); assertThrows(IllegalArgumentException.class, () -> IndependentMechanicCatalog.verifyCurrent(note));
    }
    @Test void coercedGenerationChangedOriginAndMissingFieldsAreNeverValidProjections() {
        var note = read(catalog(), "ability", "snow-cloak");
        var coerced = note.deepCopy(); coerced.put("generation", "9"); assertThrows(IllegalArgumentException.class, () -> IndependentMechanicFacts.validate(coerced));
        var url = note.deepCopy(); url.put("sourceUrl", "https://example.com/"); assertThrows(IllegalArgumentException.class, () -> IndependentMechanicFacts.validate(url));
        var missing = note.deepCopy(); missing.remove("reviewStatus"); assertThrows(IllegalArgumentException.class, () -> IndependentMechanicFacts.validate(missing));
        var changed = note.deepCopy(); changed.put("descriptionEn", "Unsupported source description"); assertThrows(IllegalArgumentException.class, () -> IndependentMechanicFacts.validate(changed));
    }
    @Test void resultsAreDefensiveCopiesRatherThanMutableSharedCatalogEntries() {
        var c = catalog(); var one = read(c, "move", "ice-beam"); one.put("explanationZh", "changed");
        assertTrue(read(c, "move", "ice-beam").path("explanationZh").asText().contains("10%"));
    }
    @Test void toolRequiresTheRunOwnerAndBothVersionsEvenForOfflineSources() {
        var runs = new InMemoryRunRepository(); runs.save(AgentRun.create("run-mechanic", "session", "conversation", "alice", "test", "pokemon-scarlet-violet", "casual-singles", "casual-v1", "zh-CN", null, "pokeapi-reference-v1", null, null, "v1", "trace", null, null));
        var tool = new PokemonMechanicsController.Tools(catalog(), new RunToolScope(runs));
        ToolExecutionContextHolder.set(new ToolExecutionContext("alice", "run-mechanic", Set.of("trainer"), "trace", null, null));
        try {
            assertTrue(tool.lookupPokemonMechanics("ability", "torrent", "pokeapi-reference-v1", "pokemon-scarlet-violet").path("descriptionEn").asText().contains("1/3"));
            assertThrows(IllegalArgumentException.class, () -> tool.lookupPokemonMechanics("ability", "torrent", "other", "pokemon-scarlet-violet"));
            assertThrows(IllegalArgumentException.class, () -> tool.lookupPokemonMechanics("ability", "torrent", "pokeapi-reference-v1", "pokemon-sword-shield"));
            ToolExecutionContextHolder.set(new ToolExecutionContext("bob", "run-mechanic", Set.of("trainer"), "trace", null, null));
            assertThrows(IllegalStateException.class, () -> tool.lookupPokemonMechanics("ability", "torrent", "pokeapi-reference-v1", "pokemon-scarlet-violet"));
        } finally { ToolExecutionContextHolder.clear(); }
    }
    @Test void httpExposesBoundedSourceNotesAndRejectsVersionChanges() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new PokemonMechanicsController(catalog())).build();
        mvc.perform(get("/pokemon/reference/mechanics/ability/torrent").param("dataReleaseId", "pokeapi-reference-v1").param("gameReleaseId", "pokemon-scarlet-violet"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.generation").value(9)).andExpect(jsonPath("$.reviewStatus").value("PROJECT_PARAPHRASE_PENDING_HUMAN_REVIEW"));
        mvc.perform(get("/pokemon/reference/mechanics/ability/torrent").param("dataReleaseId", "other").param("gameReleaseId", "pokemon-scarlet-violet")).andExpect(status().isBadRequest());
        mvc.perform(get("/pokemon/reference/mechanics/move/protect").param("dataReleaseId", "pokeapi-reference-v1").param("gameReleaseId", "pokemon-scarlet-violet"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404)).andExpect(jsonPath("$.message").value("尚未收录该资源的第二来源机制说明"));
    }
}
