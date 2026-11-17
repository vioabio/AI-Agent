package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vio.vioaiagent.pokemon.domain.PokemonFact;
import com.vio.vioaiagent.pokemon.engine.*;
import com.vio.vioaiagent.pokemon.reference.LocalPokeApiRepository;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class DamageAnswerBlockTest {
    public static LocalDamageEngine.Request request(String move,String weather){return new LocalDamageEngine.Request(9,"pokeapi-reference-v1",new LocalDamageEngine.Combatant("squirtle",null),new LocalDamageEngine.Combatant("squirtle",null),move,new LocalDamageEngine.Field("Singles",weather,null));}
    public static JsonNode calculate(LocalDamageEngine.Request request){
        return calculate(request,"https://pokeapi.co/api/v2/pokemon/7/");
    }
    private static JsonNode calculate(LocalDamageEngine.Request request,String source){
        var repository=mock(LocalPokeApiRepository.class);
        var fact=new PokemonFact(7,7,7,"squirtle","杰尼龟","squirtle","杰尼龟","pokeapi-reference-v1","pokemon-scarlet-violet",source,"sha256:"+"a".repeat(64),"LOCAL_REFERENCE","水",null,44,48,65,50,64,43);
        when(repository.detail(anyString(),anyString())).thenReturn(new LocalPokeApiRepository.Detail(fact,List.of("torrent","rain-dish"),List.of(),"2026-11-13T00:00:00Z","fixture"));
        return new VerifiedLocalDamageEngine(repository,new ObjectMapper(),"node",Path.of("tools/pokemon-engine/calculate-v2.cjs").toAbsolutePath().toString()).calculate(request);
    }
    @Test void realEngineNormalAndZeroResultsKeepExactConditionsAndNumbers(){
        for(String move:List.of("tackle","water-gun")){
            var request=request(move,move.equals("water-gun")?"Harsh Sunshine":null);var result=calculate(request);var block=DamageAnswerBlock.from(request,result);
            assertEquals(result.path("range").path("maximum").asInt(),block.maximum());assertEquals(result.path("percent").path("maximum").asDouble(),block.maximumPercent());
            assertEquals(List.of(31,31,31,31,31,31),block.attacker().ivs());assertEquals(50,block.attacker().level());
            assertEquals(block.blockId(),DamageAnswerBlock.from(request,result.deepCopy()).blockId());
            if(move.equals("water-gun"))assertEquals(0,block.maximum());else assertTrue(block.minimum()>0);
        }
    }
    @Test void staleProofOrDifferentRequestCannotPublishNumericallyPlausibleBlock(){
        var request=request("tackle",null);var result=calculate(request);var changed=result.deepCopy();
        int maximum=result.path("range").path("maximum").asInt()+1;
        ((ObjectNode)changed.path("range")).put("maximum",maximum);((ObjectNode)changed.path("percent")).put("maximum",100.0*maximum/changed.path("defenderHp").asInt());
        assertThrows(IllegalArgumentException.class,()->DamageAnswerBlock.from(request,changed));
        assertThrows(IllegalArgumentException.class,()->DamageAnswerBlock.from(request("water-gun",null),result));
    }
    @Test void evidenceUsesActualPublicSourcesAndBindsRequestAndResult(){
        var request=request("tackle",null);var result=calculate(request);var block=DamageAnswerBlock.from(request,result);
        var evidence=block.evidence();assertNotNull(evidence);
        assertEquals(block.blockId(),evidence.targetBlockId());assertEquals(block.evidenceDigest(),evidence.projectionDigest());
        assertEquals("sha256:"+DamageResultContract.digest(request),evidence.requestDigest());
        assertEquals("0.12.0",evidence.engineVersion());
        assertEquals(result.path("attackerSource").asText(),evidence.sources().get(0).url());
        assertEquals(result.path("defenderChecksum").asText(),evidence.sources().get(1).checksum());
        assertThrows(UnsupportedOperationException.class,()->evidence.sources().clear());
        assertThrows(IllegalArgumentException.class,()->new EvidenceAnswerBlock(evidence.schemaVersion(),evidence.kind(),"damage-"+"0".repeat(64),evidence.requestDigest(),evidence.projectionDigest(),evidence.engineVersion(),evidence.scope(),evidence.sources()));
    }
    @Test void absentOrUnsafeSourceDoesNotInventProvenanceOrDiscardValidNumbers(){
        var request=request("tackle",null);var result=calculate(request);
        for(String url:List.of("", "javascript:alert(1)","file:///secret","https://evil.example/api/v2/pokemon/7/","https://pokeapi.co/api/v2/pokemon/7/?token=private")){
            ObjectNode changed=result.deepCopy();changed.put("attackerSource",url);
            var block=DamageAnswerBlock.from(request,changed);assertNull(block.evidence());
            assertEquals(result.path("range").path("maximum").asInt(),block.maximum());
        }
        ObjectNode absent=result.deepCopy();absent.remove("defenderChecksum");
        assertNull(DamageAnswerBlock.from(request,absent).evidence());
    }
    @Test void actualSlugReferenceKeepsDamageEvidenceAndSafeFormNamesAreAccepted(){
        var request=request("tackle",null);
        var block=DamageAnswerBlock.from(request,calculate(request,"https://pokeapi.co/api/v2/pokemon/squirtle/"));
        assertNotNull(block.evidence());
        assertTrue(block.evidence().sources().stream().allMatch(source->source.url().equals("https://pokeapi.co/api/v2/pokemon/squirtle/")));
        for(String key:List.of("mr-mime","vulpix-alola","porygon2","10061"))
            assertDoesNotThrow(()->new EvidenceAnswerBlock.Source("attacker","https://pokeapi.co/api/v2/pokemon/"+key+"/","sha256:"+"a".repeat(64)));
        for(String key:List.of("../7","%37","squirtle?token=x","Squirtle","squirtle/extra","squirtle-","a".repeat(129)))
            assertThrows(IllegalArgumentException.class,()->new EvidenceAnswerBlock.Source("attacker","https://pokeapi.co/api/v2/pokemon/"+key+"/","sha256:"+"a".repeat(64)));
    }
    @Test void noItemAliasIsCanonicalizedOnlyForReadOnlyCalculationAndPreservesOtherFields(){
        for(String alias:List.of("none","None","NONE")){
            var saved=new com.vio.vioaiagent.pokemon.domain.TeamBuild(List.of("tackle"),"Torrent",alias,"hardy",50,null,null,"water");
            var combatant=new LocalDamageEngine.Combatant("squirtle",saved);
            assertEquals(alias,saved.item());assertEquals("",combatant.build().item());
            var expected=new com.vio.vioaiagent.pokemon.domain.TeamBuild(saved.moves(),saved.ability(),"",saved.nature(),saved.level(),saved.evs(),saved.ivs(),saved.teraType());
            assertEquals(expected,combatant.build());
        }
        var build=new com.vio.vioaiagent.pokemon.domain.TeamBuild(List.of(),"","Leftovers","hardy",50,null,null);
        assertSame(build,new LocalDamageEngine.Combatant("squirtle",build).build());
    }
}
