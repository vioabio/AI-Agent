package com.vio.vioaiagent.pokemon.reference;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vio.vioaiagent.agent.*;
import com.vio.vioaiagent.run.*;
import com.vio.vioaiagent.tools.governance.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MachineReferenceTest {
    @TempDir Path directory;
    final ObjectMapper json=new ObjectMapper();
    MachineReferenceFacts.Report read(LocalMachineReferences r,String slug){return r.read(7,slug,"pokeapi-reference-v1","pokemon-scarlet-violet");}
    @Test void fourPinnedTriplesHaveTheirTmNumberRatherThanMachineResourceId(){
        var c=MachineReferenceCatalog.current();assertEquals("TM011",c.find("water-pulse").label());assertEquals(1826,c.find("water-pulse").machineId());
        assertEquals("TM135",c.find("ice-beam").label());assertEquals("TM007",c.find("protect").label());assertEquals("TM142",c.find("hydro-pump").label());assertNull(c.find("surf"));
    }
    @Test void changedCatalogueOrSourceFailsClosed(){
        for(String target:List.of("catalog-v1.json","source/machine-1826.json")){
            ResourceLoader bad=new DefaultResourceLoader(){@Override public Resource getResource(String location){return location.endsWith(target)?new ByteArrayResource("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)):super.getResource(location);}};
            assertThrows(IllegalStateException.class,()->new MachineReferenceCatalog(json,bad));
        }
    }
    @Test void matchedOutputBindsTheMemberLearningRowAndPinnedTriple(){var f=new BuildCheckFixture(directory);var r=read(new LocalMachineReferences(f.learning),"ice-beam");assertEquals("MATCHED",r.status());assertEquals("TM135",r.machine().label());assertEquals(r,MachineReferenceFacts.validate(json.valueToTree(r)));assertEquals(1,f.networkCalls.get());}
    @Test void absentMemberMethodDoesNotExposeACatalogueNumber(){var f=new BuildCheckFixture(directory);var r=read(new LocalMachineReferences(f.learning),"hydro-pump");assertEquals("MEMBER_MACHINE_NOT_RECORDED",r.status());assertNull(r.machine());assertNotNull(r.learning());}
    @Test void unlistedMachineAndFailedReadStaySeparate(){var f=new BuildCheckFixture(directory);assertEquals("NOT_CATALOGUED",read(new LocalMachineReferences(f.learning),"surf").status());var missing=mock(LocalLearnsetReferences.class);when(missing.read(anyLong(),anyString(),anyString(),anyString())).thenThrow(new IllegalStateException("private detail"));var r=read(new LocalMachineReferences(missing),"ice-beam");assertEquals("REFERENCE_UNAVAILABLE",r.status());assertNull(r.machine());assertFalse(json.valueToTree(r).toString().contains("private detail"));}
    @Test void wrongArgumentsAndTamperedLabelsCannotAcquireEvidence()throws Exception{
        var f=new BuildCheckFixture(directory);var r=read(new LocalMachineReferences(f.learning),"ice-beam");String args="{\"pokemonId\":7,\"moveSlug\":\"ice-beam\",\"dataReleaseId\":\"pokeapi-reference-v1\",\"gameReleaseId\":\"pokemon-scarlet-violet\"}";
        assertEquals(r,MachineReferenceFacts.bound(json.writeValueAsString(r),args));assertThrows(IllegalArgumentException.class,()->MachineReferenceFacts.bound(json.writeValueAsString(r),args.replace("ice-beam","protect")));
        var tree=(ObjectNode)json.valueToTree(r);((ObjectNode)tree.path("machine")).put("label","TM999");assertThrows(IllegalArgumentException.class,()->MachineReferenceFacts.validate(tree));
        var e=MachineResultEvidence.capture(ToolCapabilityCatalog.find("lookupPokemonMachine").orElseThrow(),json.convertValue(json.readTree(args),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}),"digest",json.writeValueAsString(r));assertEquals("VERIFIED",e.get("status"));
    }
    @Test void unsupportedVersionAndCancellationStopBeforeIo(){var f=new BuildCheckFixture(directory);var service=new LocalMachineReferences(f.learning);assertThrows(IllegalArgumentException.class,()->service.read(7,"ice-beam","pokeapi-reference-v1","pokemon-sword-shield"));Thread.currentThread().interrupt();try{assertThrows(java.util.concurrent.CancellationException.class,()->read(service,"ice-beam"));}finally{Thread.interrupted();}assertEquals(0,f.networkCalls.get());}
    @Test void wrongMemberLearningRowsAreRejected(){var f=new BuildCheckFixture(directory);var source=f.learning.read(7,"ice-beam","pokeapi-reference-v1","pokemon-scarlet-violet");assertThrows(IllegalArgumentException.class,()->MachineReferenceFacts.create(478,"ice-beam","pokeapi-reference-v1","pokemon-scarlet-violet",source));}
    @Test void httpReturnsBoundFactsAnd400ForOtherVersions()throws Exception{var f=new BuildCheckFixture(directory);var mvc=MockMvcBuilders.standaloneSetup(new PokemonMachineController(new LocalMachineReferences(f.learning))).build();mvc.perform(get("/pokemon/reference/machines/7/ice-beam").param("dataReleaseId","pokeapi-reference-v1").param("gameReleaseId","pokemon-scarlet-violet")).andExpect(status().isOk()).andExpect(jsonPath("$.machine.label").value("TM135"));mvc.perform(get("/pokemon/reference/machines/7/ice-beam").param("dataReleaseId","pokeapi-reference-v1").param("gameReleaseId","pokemon-sword-shield")).andExpect(status().isBadRequest());}
    @Test void currentRunOwnershipAndVersionsAreStillRequired(){var f=new BuildCheckFixture(directory);var runs=new InMemoryRunRepository();runs.save(AgentRun.create("run-machine","session","conversation","alice","test","pokemon-scarlet-violet","casual-singles","casual-v1","zh-CN",null,"pokeapi-reference-v1",null,null,"v1","trace",null,null));var tool=new PokemonMachineController.Tools(new LocalMachineReferences(f.learning),new RunToolScope(runs));ToolExecutionContextHolder.set(new ToolExecutionContext("bob","run-machine",Set.of("trainer"),"trace",null,null));try{assertThrows(IllegalStateException.class,()->tool.lookupPokemonMachine(7,"ice-beam","pokeapi-reference-v1","pokemon-scarlet-violet"));assertEquals(0,f.networkCalls.get());}finally{ToolExecutionContextHolder.clear();}}
    @Test void additionalMachineRequestDoesNotDisappearIntoBuildOnlyRoute(){var f=new BuildCheckFixture(directory);assertTrue(BuildCheckRequestRoute.select("请检查下列配招并查询学习器编号\n"+json.valueToTree(f.request())).isEmpty());}
}
