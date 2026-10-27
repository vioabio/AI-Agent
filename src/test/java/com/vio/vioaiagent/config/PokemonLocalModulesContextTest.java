package com.vio.vioaiagent.config;

import com.vio.vioaiagent.pokemon.reference.*;
import com.vio.vioaiagent.pokemon.repository.*;
import com.vio.vioaiagent.pokemon.engine.LocalDamageEngine;
import com.vio.vioaiagent.pokemon.guides.LocalGuideSearch;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.profiles.active=local,deepseek", "vio.model.deepseek.api-key=offline-test-only",
        "spring.config.name=application-test", "vio.model.provider=deepseek", "vio.pokemon.reference.enabled=true", "vio.local-history.enabled=true",
        "vio.damage-engine.enabled=true","vio.local-guides.enabled=true","vio.local-guides.location=classpath:pokemon-guides/index.json","spring.ai.mcp.client.enabled=false"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class PokemonLocalModulesContextTest {
    @Autowired ApplicationContext context;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @DynamicPropertySource static void paths(DynamicPropertyRegistry registry) throws Exception {
        Path root=Files.createTempDirectory("vio-pokemon-modules-");
        registry.add("vio.local-history.path",()->root.resolve("history.sqlite").toString());
        registry.add("vio.local-teams.path",()->root.resolve("teams.sqlite").toString());
        registry.add("vio.local-guides.path",()->root.resolve("guides.sqlite").toString());
        registry.add("vio.pokemon.reference.path",()->root.resolve("reference").toString());
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var client=new PokeApiClient(mapper,root.resolve("reference"),url->{
            if(url.contains("pokemon-species"))return "{\"id\":7,\"name\":\"squirtle\",\"generation\":{\"name\":\"generation-i\"},\"names\":[]}";
            var data=mapper.createObjectNode().put("id",7).put("name","squirtle");
            data.putObject("species").put("name","squirtle");data.putArray("types").addObject().putObject("type").put("name","water");
            int[] values={44,48,65,50,64,43};String[] names={"hp","attack","defense","special-attack","special-defense","speed"};
            var stats=data.putArray("stats");for(int i=0;i<6;i++)stats.addObject().put("base_stat",values[i]).putObject("stat").put("name",names[i]);
            return data.toString();
        });
        client.read("pokemon","7");client.read("pokemon","squirtle");client.read("pokemon-species","squirtle");
    }
    @Test void localModulesWireWithoutStartingNetworkOrNodeProcesses() {
        assertInstanceOf(LocalPokeApiRepository.class,context.getBean(PokemonFactRepository.class));
        assertInstanceOf(SqliteSavedTeamRepository.class,context.getBean(SavedTeamRepository.class));
        assertInstanceOf(com.vio.vioaiagent.pokemon.engine.VerifiedLocalDamageEngine.class,context.getBean(LocalDamageEngine.class));assertNotNull(context.getBean(LocalGuideSearch.class));
        assertFalse(context.containsBean("dataSource"));
        var tools=context.getBean("allTools",org.springframework.ai.tool.ToolCallback[].class);
        var names=java.util.Arrays.stream(tools).map(t->t.getToolDefinition().name()).toList();
        assertTrue(names.containsAll(java.util.List.of("lookupPokemonReference","lookupPokemonResource","calculateBattleDamage","searchPokemonGuides")));
    }
    @Test void realHttpMappingsReadCachedFactsAndSaveConfiguredTeams() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/pokemon/facts")
                .param("dataReleaseId",LocalPokeApiRepository.RELEASE).param("slug","squirtle"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.items[0].releaseStatus").value("LOCAL_REFERENCE"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/teams")
                .header("X-User-Id","local-test-owner").header("Idempotency-Key","team-config-http")
                .contentType("application/json").content("""
                {"name":"HTTP team","rulesetId":"casual-v1","dataReleaseId":"pokeapi-reference-v1",
                 "members":[{"formId":7,"slot":1,"build":{"moves":["surf"],"ability":"torrent","nature":"modest","level":50,"evs":{"spa":252}}}]}
                """))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.members[0].build.moves[0]").value("surf"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/pokemon/guides")
                .param("gameReleaseId","pokemon-scarlet-violet").param("query","努力值性格"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].document.id").value("training-configuration-v1"));
    }
    @Test void guideHttpReturnsRealChunksLocatorsAndStrictScopeWithoutAmbiguousRetrieverBeans() throws Exception {
        var get=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/pokemon/guides/retrieve")
                .param("gameReleaseId","pokemon-scarlet-violet").param("query","努力值性格");
        var response=mvc.perform(get).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        var data=new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        String id=data.get(0).path("view").path("chunk").path("id").asText();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/pokemon/guides/chunks/{id}",id))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.citation.chunkId").value(id))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.document.id").value("training-configuration-v1"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/pokemon/guides/chunks/unknown"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/pokemon/guides/retrieve")
                .param("query","努力值性格").param("gameReleaseId","pokemon-scarlet-violet").param("battleFormatId","vgc"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json("[]"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/pokemon/guides/index"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.documentCount").value(4))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.embeddingModel").value("none"));
        assertEquals(1,context.getBeansOfType(com.vio.vioaiagent.rag.retrieval.KnowledgeRetriever.class).size());
    }
}
