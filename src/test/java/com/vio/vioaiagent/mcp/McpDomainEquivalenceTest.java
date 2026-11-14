package com.vio.vioaiagent.mcp;

import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.pokemon.reference.*;
import com.vio.vioaiagent.pokemon.engine.*;
import com.vio.vioaiagent.security.*;
import com.vio.vioaiagent.tools.governance.*;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Compares public controller method results with MCP gateway results; not an HTTP authentication test. */
class McpDomainEquivalenceTest {
    @TempDir Path cache;
    @Test void sameFactsBuildAndDamageInputsProduceIdenticalDomainResults() throws Exception {
        var json=new ObjectMapper();var client=McpStdioFixtureMain.client(cache);
        var repository=new LocalPokeApiRepository(json,client);
        var checks=new LocalBuildCheckService(json,repository,new LocalLearnsetReferences(json,client),client);
        var engine=new VerifiedLocalDamageEngine(repository,json,"node",Path.of("tools/pokemon-engine/calculate-v2.cjs").toAbsolutePath().toString());
        var callbacks=org.springframework.ai.support.ToolCallbacks.from(new PokemonReferenceTools(repository),
                new PokemonBuildCheckController.Tools(checks,null),new DamageEngineController.Tools(engine,null));
        var hitl=new HitlManager();var gateway=new ToolExecutionGateway(hitl,null,null,null,ToolAuditSink.noop(),null,null,false,new ToolExecutionProperties(2,0,500));
        try {
            var adapter=new ReadOnlyMcpAdapter(callbacks,gateway);
            for(String query:List.of("7","squirtle","杰尼龟")) {
                var expected=json.readTree(json.writeValueAsBytes(new PokemonReferenceController(repository).detail(query,LocalPokeApiRepository.RELEASE)));
                assertEquals(expected,result(json,adapter,"lookupPokemonReference",Map.of("query",query,"dataReleaseId",LocalPokeApiRepository.RELEASE)));
            }
            var request=json.readTree("{\"pokemonId\":7,\"dataReleaseId\":\"pokeapi-reference-v1\",\"gameReleaseId\":\"pokemon-scarlet-violet\"}");
            assertEquals(json.readTree(json.writeValueAsBytes(new PokemonBuildCheckController(checks).check(request))),result(json,adapter,"checkPokemonBuild",Map.of("request",json.convertValue(request,Map.class))));
            for(String move:List.of("tackle","water-gun")) {
                var damage=new LocalDamageEngine.Request(9,LocalPokeApiRepository.RELEASE,new LocalDamageEngine.Combatant("squirtle",null),new LocalDamageEngine.Combatant("squirtle",null),move,new LocalDamageEngine.Field("Singles",move.equals("water-gun")?"Harsh Sunshine":null,null));
                JsonNode expected=new DamageEngineController(engine).calculate(damage);
                assertEquals(expected,result(json,adapter,"calculateBattleDamage",Map.of("request",json.convertValue(damage,Map.class))));
                if(move.equals("water-gun"))assertEquals(0,expected.path("range").path("maximum").asInt(-1));
            }
            assertThrows(IllegalArgumentException.class,()->new PokemonReferenceController(repository).detail("7","wrong"));
            assertTrue(adapter.invoke("lookupPokemonReference",Map.of("query","7","dataReleaseId","wrong")).block(Duration.ofSeconds(3)).isError());
            var invalid=new LocalDamageEngine.Request(8,LocalPokeApiRepository.RELEASE,new LocalDamageEngine.Combatant("squirtle",null),new LocalDamageEngine.Combatant("squirtle",null),"tackle",null);
            assertThrows(IllegalArgumentException.class,()->new DamageEngineController(engine).calculate(invalid));
            assertTrue(adapter.invoke("calculateBattleDamage",Map.of("request",json.convertValue(invalid,Map.class))).block(Duration.ofSeconds(3)).isError());
        }finally{gateway.shutdown();hitl.shutdown();}
    }
    JsonNode result(ObjectMapper json,ReadOnlyMcpAdapter adapter,String tool,Map<String,Object> args)throws Exception {
        var output=adapter.invoke(tool,args).block(Duration.ofSeconds(10));assertFalse(output.isError(),tool);
        return json.readTree(((McpSchema.TextContent)output.content().getFirst()).text());
    }
}
