package com.vio.vioaiagent.pokemon.reference;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vio.vioaiagent.run.*;
import com.vio.vioaiagent.tools.governance.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LocalResourceReferencesTest {
    @TempDir Path directory;
    final ObjectMapper mapper=new ObjectMapper();
    LocalResourceReferences bundled(){return new LocalResourceReferences(mapper,new DefaultResourceLoader(),new PokeApiClient(mapper,directory,url->{throw new AssertionError("Unexpected network");}));}
    JsonNode read(LocalResourceReferences refs,String kind,String slug){return mapper.valueToTree(refs.read(kind,slug,"pokeapi-reference-v1"));}
    @Test void allTwelvePinnedResourcesAreReadableWithoutNetworkAndHaveValidProjectionHashes() throws Exception {
        var refs=bundled();var catalog=mapper.readTree(Path.of("src/main/resources/pokemon-mechanics/references-v1.json").toFile());assertEquals(12,catalog.path("records").size());
        for(var row:catalog.path("records")){var actual=read(refs,row.path("kind").asText(),row.path("slug").asText());assertEquals(row,ResourceReferenceFacts.validate(actual));}
    }
    @Test void snowUsesTheActualSvTextAndNatureStatDirectionsDoNotRepeatPriorModelErrors() {
        var refs=bundled();var snow=read(refs,"ability","snow-cloak");assertTrue(snow.path("explanationZh").asText().contains("下雪"));assertFalse(snow.path("explanationZh").asText().contains("冰雹"));
        assertEquals("scarlet-violet",snow.path("descriptionVersionGroup").asText());assertEquals("PROJECT_PARAPHRASE_PENDING_HUMAN_REVIEW",snow.path("explanationStatus").asText());
        assertEquals("speed",read(refs,"nature","timid").path("increasedStat").asText());assertEquals("attack",read(refs,"nature","timid").path("decreasedStat").asText());
        assertEquals("special-attack",read(refs,"nature","modest").path("increasedStat").asText());
    }
    @Test void statusMoveNullFieldsRemainNullAndCannotBeReinterpretedAsZeroOrGuaranteedHit() {
        var protect=read(bundled(),"move","protect");assertTrue(protect.get("power").isNull());assertTrue(protect.get("accuracy").isNull());assertEquals(4,protect.path("priority").asInt());
        var ice=read(bundled(),"move","ice-beam");assertEquals(90,ice.path("power").asInt());assertEquals(100,ice.path("accuracy").asInt());
    }
    ObjectNode fallback(String name) throws Exception {
        return (ObjectNode)mapper.readTree("{\"id\":65,\"name\":\""+name+"\",\"names\":[],\"flavor_text_entries\":[{\"flavor_text\":\"OLD_GENERATION_MUST_NOT_APPEAR\",\"language\":{\"name\":\"en\"},\"version_group\":{\"name\":\"sword-shield\"}}]}");
    }
    @Test void fallbackDoesNotBorrowOldGenerationTextAndExistingCacheIsReused() throws Exception {
        var calls=new AtomicInteger();var body=fallback("overgrow").toString();var refs=new LocalResourceReferences(mapper,new DefaultResourceLoader(),new PokeApiClient(mapper,directory,url->{calls.incrementAndGet();return body;}));
        var first=read(refs,"ability","overgrow");assertFalse(first.has("descriptionExcerpt"));assertFalse(first.toString().contains("OLD_GENERATION"));assertEquals("LIVE_CACHE_REFERENCE",first.path("sourceKind").asText());
        assertEquals(first,read(refs,"ability","overgrow"));assertEquals(1,calls.get());
    }
    @Test void corruptCacheAndAmbiguousSvDescriptionsAreRejectedRatherThanSilentlyInventingFields() throws Exception {
        var body=fallback("overgrow");var entries=(com.fasterxml.jackson.databind.node.ArrayNode)body.path("flavor_text_entries");
        for(int i=0;i<2;i++)entries.add(mapper.readTree("{\"flavor_text\":\"ambiguous\",\"language\":{\"name\":\"en\"},\"version_group\":{\"name\":\"scarlet-violet\"}}"));
        var refs=new LocalResourceReferences(mapper,new DefaultResourceLoader(),new PokeApiClient(mapper,directory,url->body.toString()));
        assertThrows(IllegalArgumentException.class,()->read(refs,"ability","overgrow"));
        Path cache=directory.resolve("ability-overgrow.json");var raw=(ObjectNode)mapper.readTree(cache.toFile());raw.put("sha256","sha256:"+"0".repeat(64));Files.writeString(cache,raw.toString());
        assertThrows(IllegalStateException.class,()->read(refs,"ability","overgrow"));
    }
    @Test void badCatalogHashAndCoercedNumericFieldsCannotBecomeFacts() throws Exception {
        var loader=new DefaultResourceLoader(){@Override public Resource getResource(String location){if(location.endsWith(".meta.json"))return new ByteArrayResource("{\"catalogSha256\":\"wrong\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));return super.getResource(location);}};
        assertThrows(IllegalStateException.class,()->new LocalResourceReferences(mapper,loader,new PokeApiClient(mapper,directory,url->{throw new AssertionError();})));
        var ice=(ObjectNode)read(bundled(),"move","ice-beam");ice.put("power","90");assertThrows(IllegalArgumentException.class,()->ResourceReferenceFacts.seal(ice));
        var missing=(ObjectNode)read(bundled(),"nature","timid");missing.remove("increasedStat");assertThrows(IllegalArgumentException.class,()->ResourceReferenceFacts.seal(missing));
    }
    @Test void toolStillRequiresTheAuthenticatedRunOwnerAndExactReleaseBeforeServingBundledData() {
        var runs=new InMemoryRunRepository();runs.save(AgentRun.create("run-resources","session","conversation","alice","test","pokemon-scarlet-violet","casual-singles","casual-v1","zh-CN",null,"pokeapi-reference-v1",null,null,"v1","trace",null,null));
        var tools=new PokemonReferenceTools(null,new RunToolScope(runs),bundled());
        ToolExecutionContextHolder.set(new ToolExecutionContext("alice","run-resources",Set.of("trainer"),"trace",null,null));
        try{assertEquals("ice-beam",tools.lookupPokemonResource("move","ice-beam","pokeapi-reference-v1").get("slug"));
            assertThrows(IllegalArgumentException.class,()->tools.lookupPokemonResource("move","ice-beam","other"));
            ToolExecutionContextHolder.set(new ToolExecutionContext("bob","run-resources",Set.of("trainer"),"trace",null,null));
            assertThrows(IllegalStateException.class,()->tools.lookupPokemonResource("move","ice-beam","pokeapi-reference-v1"));
        }finally{ToolExecutionContextHolder.clear();}
    }
}
