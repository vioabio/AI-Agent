package com.vio.vioaiagent.pokemon;

import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.pokemon.engine.LocalDamageEngine;
import com.vio.vioaiagent.pokemon.reference.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalDamageEngineTest {
    @TempDir Path directory;
    private final ObjectMapper mapper=new ObjectMapper();
    private LocalDamageEngine engine(boolean mismatch) {
        return engine(mismatch,Path.of("tools/pokemon-engine/calculate.cjs"));
    }
    private LocalDamageEngine engine(boolean mismatch,Path script) {
        var repository=new LocalPokeApiRepository(mapper,new PokeApiClient(mapper,directory,url->{
            String name=url.contains("charmander")?"charmander":"squirtle";
            int id=name.equals("squirtle")?7:4;
            if(url.contains("pokemon-species"))return "{\"id\":"+id+",\"name\":\""+name+"\",\"generation\":{\"name\":\"generation-i\"},\"names\":[]}";
            int[] values=id==7?new int[]{mismatch?45:44,48,65,50,64,43}:new int[]{39,52,43,60,50,65};
            String[] names={"hp","attack","defense","special-attack","special-defense","speed"};
            var data=mapper.createObjectNode().put("id",id).put("name",name);
            data.putArray("abilities").addObject().putObject("ability").put("name",id==7?"torrent":"blaze");
            data.putObject("species").put("name",name);data.putArray("types").addObject().putObject("type").put("name",id==7?"water":"fire");
            var stats=data.putArray("stats");for(int i=0;i<6;i++)stats.addObject().put("base_stat",values[i]).putObject("stat").put("name",names[i]);
            return data.toString();
        }));
        return new com.vio.vioaiagent.pokemon.engine.VerifiedLocalDamageEngine(repository,mapper,"node",script.toAbsolutePath().toString());
    }
    private LocalDamageEngine.Request request(){return new LocalDamageEngine.Request(9,LocalPokeApiRepository.RELEASE,
            new LocalDamageEngine.Combatant("squirtle",null),new LocalDamageEngine.Combatant("charmander",null),"surf",new LocalDamageEngine.Field("Singles",null,null));}
    @Test void javaCallsRealNodeEngineAndPreservesEvidenceAndDefaults(){
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(Path.of("tools/pokemon-engine/node_modules/@smogon/calc/package.json")),"Install the pinned local Node engine to run this integration test");
        JsonNode result=engine(false).calculate(request());
        assertTrue(result.path("range").path("minimum").asInt()>0);
        assertEquals("0.12.0",result.path("engineVersion").asText());
        assertEquals(50,result.path("assumptions").path("attacker").path("level").asInt());
        assertTrue(result.path("attackerChecksum").asText().startsWith("sha256:"));
        assertEquals("VERIFIED",result.path("conditionValidation").path("status").asText());
        assertEquals(2,result.path("conditionValidation").path("engineDefaultAbilitySides").size());
        assertThrows(IllegalArgumentException.class,()->engine(false).calculate(new LocalDamageEngine.Request(8,LocalPokeApiRepository.RELEASE,request().attacker(),request().defender(),"surf",null)));
    }
    @Test void rejectsReferenceAndEngineStatMismatch(){
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(Path.of("tools/pokemon-engine/node_modules/@smogon/calc/package.json")));
        assertThrows(IllegalStateException.class,()->engine(true).calculate(request()));
    }
    @Test void rejectsARealProcessReturningPlausibleNumbersForDifferentConditions() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(Path.of("tools/pokemon-engine/node_modules/@smogon/calc/package.json")));
        var result=(com.fasterxml.jackson.databind.node.ObjectNode)engine(false).calculate(request());
        ((com.fasterxml.jackson.databind.node.ObjectNode)result.path("assumptions").path("attacker")).put("level",60);
        Path script=directory.resolve("tampered.cjs");
        Files.writeString(script,"process.stdin.resume();process.stdin.on('end',()=>process.stdout.write("+mapper.writeValueAsString(result.toString())+"));");
        assertThrows(IllegalStateException.class,()->engine(false,script).calculate(request()));
    }
}
