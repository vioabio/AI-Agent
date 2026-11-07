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

class MachineAcquisitionCatalogTest {
    @TempDir Path directory;
    MachineAcquisitionCatalog.Reference read(String move){return MachineAcquisitionCatalog.read(move,"pokemon-scarlet-violet");}
    @Test void sharedRegionsAreAnExplicitSubsetAndDoNotMergeTheCraterWithAreaZero(){
        assertEquals(List.of("south-province-area-one","south-province-area-four"),read("protect").regions().stream().map(MachineAcquisitionCatalog.Region::id).toList());
        assertEquals(List.of("socarrat-trail"),read("water-pulse").regions().stream().map(MachineAcquisitionCatalog.Region::id).toList());
        assertEquals(List.of("casseroya-lake"),read("hydro-pump").regions().stream().map(MachineAcquisitionCatalog.Region::id).toList());
        assertEquals("COMMON_REGION_REFERENCE",read("hydro-pump").status());assertEquals("NOT_CROSS_CHECKED",read("hydro-pump").unlockStatus());
    }
    @Test void missingLocationRowsCannotMeanNoAcquisitionAndSingleSourceBossIsNotPublished(){
        var r=read("ice-beam");assertEquals("SOURCE_INCOMPLETE",r.status());assertTrue(r.regions().isEmpty());assertEquals(2,r.sources().size());
        String answer=MachineAcquisitionCatalog.answer("ice-beam");assertTrue(answer.contains("不能据此断言没有获取地点"));assertFalse(answer.contains("Eri"));assertFalse(answer.contains("Glaseado"));
    }
    @Test void unsupportedAndUncataloguedRequestsRemainSeparate(){
        assertEquals("NOT_CATALOGUED",read("surf").status());assertNull(read("surf").label());assertTrue(read("surf").sources().isEmpty());
        assertThrows(IllegalArgumentException.class,()->MachineAcquisitionCatalog.read("protect","pokemon-sword-shield"));assertThrows(IllegalArgumentException.class,()->read(null));assertThrows(IllegalArgumentException.class,()->read("../protect"));
    }
    @Test void returnedRegionsAndSourcesAreImmutable(){
        assertThrows(UnsupportedOperationException.class,()->read("protect").regions().clear());assertThrows(UnsupportedOperationException.class,()->read("protect").sources().clear());assertEquals(2,read("protect").regions().size());
    }
    @Test void invalidResourcesFailAndLineEndingsAreNormalized()throws Exception{
        for(byte[] bytes:List.of("{}".getBytes(StandardCharsets.UTF_8),new byte[30001]))assertThrows(IllegalStateException.class,()->new MachineAcquisitionCatalog(loader(new ByteArrayResource(bytes))));
        assertThrows(IllegalStateException.class,()->new MachineAcquisitionCatalog(loader(new FileSystemResource("missing-acquisition.json"))));
        try(var in=new ClassPathResource("pokemon-machines/acquisition-catalog-v1.json").getInputStream()){
            String crlf=new String(in.readAllBytes(),StandardCharsets.UTF_8).replace("\r\n","\n").replace("\n","\r\n");assertDoesNotThrow(()->new MachineAcquisitionCatalog(loader(new ByteArrayResource(crlf.getBytes(StandardCharsets.UTF_8)))));
        }
    }
    ResourceLoader loader(Resource resource){return new DefaultResourceLoader(){@Override public Resource getResource(String location){return resource;}};}
    @Test void controllerReturnsGeneralReferenceWithoutMemberReadsAndChecksGame()throws Exception{
        var f=new BuildCheckFixture(directory);var mvc=MockMvcBuilders.standaloneSetup(new PokemonMachineController(new LocalMachineReferences(f.learning))).build();
        mvc.perform(get("/pokemon/reference/machines/acquisition/water-pulse").param("gameReleaseId","pokemon-scarlet-violet")).andExpect(status().isOk()).andExpect(jsonPath("$.regions[0].id").value("socarrat-trail")).andExpect(jsonPath("$.unlockStatus").value("NOT_CROSS_CHECKED"));
        mvc.perform(get("/pokemon/reference/machines/acquisition/ice-beam").param("gameReleaseId","pokemon-scarlet-violet")).andExpect(status().isOk()).andExpect(jsonPath("$.regions").isEmpty());
        mvc.perform(get("/pokemon/reference/machines/acquisition/protect").param("gameReleaseId","pokemon-sword-shield")).andExpect(status().isBadRequest());assertEquals(0,f.networkCalls.get());
    }
    @Test void regionReferenceNeedsBoundIdentifierAndRecipeDisagreementDoesNotEraseSeparateRegionEvidence()throws Exception{
        var f=new BuildCheckFixture(directory);var evidence=new MachineAnswerEvidence();String args="{\"pokemonId\":7,\"moveSlug\":\"water-pulse\",\"dataReleaseId\":\"pokeapi-reference-v1\",\"gameReleaseId\":\"pokemon-scarlet-violet\"}";
        assertFalse(evidence.summary("学习器获取位置").contains("Socarrat Trail"));
        evidence.observe(new ObjectMapper().writeValueAsString(new LocalMachineReferences(f.learning).read(7,"water-pulse","pokeapi-reference-v1","pokemon-scarlet-violet")),args);
        String answer=evidence.summary("学习器获取位置");assertTrue(answer.contains("Socarrat Trail"));assertTrue(answer.contains("来源差异待核对"));assertFalse(answer.contains("Mela"));assertFalse(answer.contains("1500 LP"));assertFalse(answer.contains("catalogSha256"));
        evidence.observe("{}",args);assertFalse(evidence.summary("学习器获取位置").contains("Socarrat Trail"));
    }
}
