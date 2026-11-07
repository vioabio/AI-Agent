package com.vio.vioaiagent.pokemon.reference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.agent.MachineAnswerEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MachineCraftingGuideTest {
    @TempDir Path directory;
    @Test void fixedOfficialSourceIsScopedAndCannotBeMutated() {
        var guide = MachineCraftingGuide.read("pokemon-scarlet-violet");
        assertEquals(MachineCraftingGuide.SOURCE_URL, guide.sourceUrl());
        assertEquals(4, guide.facts().size());
        assertTrue(guide.facts().stream().anyMatch(f -> f.contains("LP")));
        assertTrue(guide.limitation().contains("不证明某个编号已解锁"));
        assertEquals("PROJECT_PARAPHRASE_PENDING_HUMAN_REVIEW", guide.reviewStatus());
        assertThrows(UnsupportedOperationException.class, () -> guide.facts().add("其他游戏"));
    }
    @Test void wrongVersionsAreNotSilentlyTreatedAsSv() {
        for (String game : new String[]{null,"","pokemon-sword-shield","scarlet-violet"})
            assertThrows(IllegalArgumentException.class, () -> MachineCraftingGuide.read(game));
    }
    @Test void tamperedMissingAndOversizedResourcesAreRejected() {
        for (byte[] bytes : List.of("{}".getBytes(StandardCharsets.UTF_8), new byte[8001])) {
            ResourceLoader bad = new DefaultResourceLoader() {
                @Override public Resource getResource(String location) { return new ByteArrayResource(bytes); }
            };
            assertThrows(IllegalStateException.class, () -> new MachineCraftingGuide(bad));
        }
        assertThrows(IllegalStateException.class, () -> new MachineCraftingGuide(new DefaultResourceLoader() {
            @Override public Resource getResource(String location) { return new FileSystemResource("missing-crafting-guide.json"); }
        }));
    }
    @Test void httpGuideDoesNotReadMemberOrNetworkAndWrongGameIs400() throws Exception {
        var fixture = new BuildCheckFixture(directory);
        var mvc = MockMvcBuilders.standaloneSetup(new PokemonMachineController(new LocalMachineReferences(fixture.learning))).build();
        mvc.perform(get("/pokemon/reference/machines/crafting-guide").param("gameReleaseId","pokemon-scarlet-violet"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scope").value(MachineCraftingGuide.SCOPE))
                .andExpect(jsonPath("$.facts.length()").value(4)).andExpect(jsonPath("$.sourceUrl").value(MachineCraftingGuide.SOURCE_URL));
        mvc.perform(get("/pokemon/reference/machines/crafting-guide").param("gameReleaseId","pokemon-sword-shield"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/pokemon/reference/machines/crafting-guide")).andExpect(status().isBadRequest());
        assertEquals(0,fixture.networkCalls.get());
    }
    @Test void generalNotesAreSeparateFromMatchedIdentifiersAndAbsentOnMissingEvidence() throws Exception {
        var fixture = new BuildCheckFixture(directory);
        var references = new LocalMachineReferences(fixture.learning);
        var json = new ObjectMapper();
        String args = "{\"pokemonId\":7,\"moveSlug\":\"ice-beam\",\"dataReleaseId\":\"pokeapi-reference-v1\",\"gameReleaseId\":\"pokemon-scarlet-violet\"}";
        var answer = new MachineAnswerEvidence();
        assertFalse(answer.summary("学习器编号").contains("通用制作说明"));
        answer.observe(json.writeValueAsString(references.read(7,"ice-beam","pokeapi-reference-v1","pokemon-scarlet-violet")),args);
        String output = answer.summary("学习器编号");
        assertTrue(output.contains("TM135"));assertTrue(output.contains("### 通用制作说明"));
        assertTrue(output.contains(MachineCraftingGuide.SOURCE_URL));assertTrue(output.contains("具体配方"));
        assertFalse(output.contains("sourcePageSha256"));assertFalse(output.contains("376ab8c9"));
        answer.observe("{}",args);
        assertFalse(answer.summary("学习器编号").contains("通用制作说明"));
        var absent = new MachineAnswerEvidence();
        absent.observe(json.writeValueAsString(references.read(7,"surf","pokeapi-reference-v1","pokemon-scarlet-violet")),args.replace("ice-beam","surf"));
        assertFalse(absent.summary("学习器编号").contains("通用制作说明"));
    }
}
