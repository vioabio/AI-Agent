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

class MachineRecipeCatalogTest {
    @TempDir Path directory;
    private MachineRecipeCatalog.Recipe read(String move){return MachineRecipeCatalog.read(move,"pokemon-scarlet-violet");}
    @Test void threeAgreedRecipesKeepLpAndAllMaterialQuantities(){
        var p=read("protect");assertEquals("TM007",p.label());assertEquals(400,p.leaguePoints());
        assertEquals(List.of(new MachineRecipeCatalog.Material("Lechonk Hair",3),new MachineRecipeCatalog.Material("Scatterbug Powder",3)),p.materials());
        var ice=read("ice-beam");assertEquals(10000,ice.leaguePoints());assertEquals(new MachineRecipeCatalog.Material("Cryogonal Ice",5),ice.materials().get(0));assertEquals(3,ice.materials().size());
        var hydro=read("hydro-pump");assertEquals(12000,hydro.leaguePoints());assertEquals(new MachineRecipeCatalog.Material("Finizen Mucus",5),hydro.materials().get(0));
        for(var r:List.of(p,ice,hydro)){assertEquals("CONSISTENT_REFERENCE",r.status());assertEquals(MachineRecipeCatalog.REVIEW,r.reviewStatus());assertEquals(2,r.sources().size());}
    }
    @Test void spellingDisagreementIsNotSilentlyCorrectedOrPublishedAsARecipe(){
        var r=read("water-pulse");assertEquals("SOURCE_DISAGREEMENT",r.status());assertNull(r.leaguePoints());assertTrue(r.materials().isEmpty());assertEquals("TM011",r.label());
        assertTrue(MachineRecipeCatalog.answer("water-pulse").contains("来源差异待核对"));assertFalse(MachineRecipeCatalog.answer("water-pulse").contains("1500"));assertFalse(MachineRecipeCatalog.answer("water-pulse").contains("Magikarp"));
    }
    @Test void unknownAndUnsupportedRequestsStayDistinct(){
        var r=read("surf");assertEquals("NOT_CATALOGUED",r.status());assertNull(r.label());assertNull(r.leaguePoints());assertTrue(r.sources().isEmpty());
        assertThrows(IllegalArgumentException.class,()->MachineRecipeCatalog.read("ice-beam","pokemon-sword-shield"));
        assertThrows(IllegalArgumentException.class,()->read("../protect"));assertThrows(IllegalArgumentException.class,()->read(null));
    }
    @Test void recipeAndSourceCollectionsCannotBeMutated(){
        var r=read("protect");assertThrows(UnsupportedOperationException.class,()->r.materials().clear());assertThrows(UnsupportedOperationException.class,()->r.sources().clear());assertEquals(2,read("protect").materials().size());
    }
    @Test void tamperedMissingOversizedSourcesFailAndCrLfIsNormalized()throws Exception{
        for(byte[] data:List.of("{}".getBytes(StandardCharsets.UTF_8),new byte[20001]))assertThrows(IllegalStateException.class,()->new MachineRecipeCatalog(loader(new ByteArrayResource(data))));
        assertThrows(IllegalStateException.class,()->new MachineRecipeCatalog(loader(new FileSystemResource("missing-recipe-catalog.json"))));
        try(var in=new ClassPathResource("pokemon-machines/recipe-catalog-v1.json").getInputStream()){
            String text=new String(in.readAllBytes(),StandardCharsets.UTF_8).replace("\r\n","\n").replace("\n","\r\n");assertDoesNotThrow(()->new MachineRecipeCatalog(loader(new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)))));
        }
    }
    private ResourceLoader loader(Resource resource){return new DefaultResourceLoader(){@Override public Resource getResource(String location){return resource;}};}
    @Test void controllerIsReadOnlyAndDoesNotFetchMemberData()throws Exception{
        var f=new BuildCheckFixture(directory);var mvc=MockMvcBuilders.standaloneSetup(new PokemonMachineController(new LocalMachineReferences(f.learning))).build();
        mvc.perform(get("/pokemon/reference/machines/recipes/protect").param("gameReleaseId","pokemon-scarlet-violet")).andExpect(status().isOk()).andExpect(jsonPath("$.leaguePoints").value(400));
        mvc.perform(get("/pokemon/reference/machines/recipes/water-pulse").param("gameReleaseId","pokemon-scarlet-violet")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SOURCE_DISAGREEMENT")).andExpect(jsonPath("$.materials").isEmpty());
        mvc.perform(get("/pokemon/reference/machines/recipes/surf").param("gameReleaseId","pokemon-scarlet-violet")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NOT_CATALOGUED"));
        mvc.perform(get("/pokemon/reference/machines/recipes/protect").param("gameReleaseId","pokemon-sword-shield")).andExpect(status().isBadRequest());
        assertEquals(0,f.networkCalls.get());
    }
    @Test void recipeAnswerRequiresBoundMachineEvidenceAndNeverChangesMissingOrDeniedCases()throws Exception{
        var f=new BuildCheckFixture(directory);var json=new ObjectMapper();var evidence=new MachineAnswerEvidence();
        String args="{\"pokemonId\":7,\"moveSlug\":\"ice-beam\",\"dataReleaseId\":\"pokeapi-reference-v1\",\"gameReleaseId\":\"pokemon-scarlet-violet\"}";
        assertFalse(evidence.summary("学习器编号").contains("Cryogonal Ice"));
        evidence.observe(json.writeValueAsString(new LocalMachineReferences(f.learning).read(7,"ice-beam","pokeapi-reference-v1","pokemon-scarlet-violet")),args);
        var answer=evidence.summary("学习器编号");assertTrue(answer.contains("Cryogonal Ice × 5"));assertTrue(answer.contains("10000 LP"));assertTrue(answer.contains("待人工及游戏内核验"));assertFalse(answer.contains("catalogSha256"));
        evidence.observe("{}",args);assertFalse(evidence.summary("学习器编号").contains("Cryogonal Ice"));
    }
}
