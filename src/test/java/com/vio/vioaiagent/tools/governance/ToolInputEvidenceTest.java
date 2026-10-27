package com.vio.vioaiagent.tools.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ToolInputEvidenceTest {
    @Test void declaredFieldAliasesKeepTheirRawValuesAndUnknownTextRemainsOmitted() {
        var proof=ToolInputEvidence.capture(card("calculateBattleDamage"),Map.of("request",Map.of("field",Map.of("gameType"," Singles ","weather","none","terrain","无"))),"digest");
        assertEquals("COMPLETE",proof.get("coverage"));assertEquals("none",((Map<?,?>)fields(proof).get("/request/field/weather")).get("value"));
        var invalid=ToolInputEvidence.capture(card("calculateBattleDamage"),Map.of("request",Map.of("field",Map.of("weather","private-secret"))),"digest");
        assertEquals("PARTIAL",invalid.get("coverage"));assertFalse(invalid.toString().contains("private-secret"));
    }
    private static ToolCapabilityCard card(String name){return new ToolCapabilityCard(name,"1",ToolCapabilityCard.RiskLevel.LOW,ToolCapabilityCard.SideEffect.NONE,true,1000,List.of("trainer"),"{}","{}");}
    private static Map<?,?> fields(Map<String,Object> value){return (Map<?,?>)value.get("fields");}
    @Test void boundedTypesAreVisibleAndUnrecognizedValuesOrFieldsAreOmitted() {
        var proof=ToolInputEvidence.capture(card("checkTypeEffectiveness"),Map.of("attackType","火","defendType","草"),"digest");
        assertEquals("COMPLETE",proof.get("coverage"));assertEquals("火",((Map<?,?>)fields(proof).get("/attackType")).get("value"));
        assertThrows(UnsupportedOperationException.class,()->((Map)fields(proof)).put("new","value"));
        var invalid=ToolInputEvidence.capture(card("getTypeChart"),Map.of("attackType","secret-value","password","secret-value"),"digest");
        assertEquals("PARTIAL",invalid.get("coverage"));assertTrue(fields(invalid).isEmpty());assertFalse(invalid.toString().contains("secret-value"));assertFalse(invalid.toString().contains("password"));
    }
    @Test void damageNamesAreHashedWhileNumbersNullsAndSuppliedShapeRemainAuditable() throws Exception {
        var mapper=new ObjectMapper();var input=mapper.readValue("""
                {"request":{"generation":9,"dataReleaseId":"pokeapi-reference-v1","attacker":{"species":"charizard","build":{"level":50,"evs":{"spa":252},"item":null}},"defender":{"species":"venusaur"},"move":"Flamethrower","field":{"gameType":"Singles","weather":null,"terrain":null}}}
                """,Map.class);
        var proof=ToolInputEvidence.capture(card("calculateBattleDamage"),input,"digest");var fields=fields(proof);
        assertEquals("COMPLETE",proof.get("coverage"));assertFalse(proof.toString().contains("charizard"));assertFalse(proof.toString().contains("Flamethrower"));
        assertEquals(252,((Map<?,?>)fields.get("/request/attacker/build/evs/spa")).get("value"));
        assertNull(((Map<?,?>)fields.get("/request/attacker/build/item")).get("value"));assertFalse(fields.containsKey("/request/defender/build/level"));
        var expected=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("\"charizard\"".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(expected,((Map<?,?>)fields.get("/request/attacker/species")).get("sha256"));
    }
    @Test void unsupportedWritesUnknownToolsAndOversizedCollectionsDoNotExposeArguments() {
        for(String tool:List.of("writeFile","searchWeb","terminal")) {
            var proof=ToolInputEvidence.capture(card(tool),Map.of("content","private-secret"),"digest");assertEquals("OMITTED",proof.get("coverage"));assertTrue(fields(proof).isEmpty());
        }
        var write=new ToolCapabilityCard("getTypeChart","1",ToolCapabilityCard.RiskLevel.LOW,ToolCapabilityCard.SideEffect.WRITE,true,1000,List.of("trainer"),"{}","{}");
        assertEquals("OMITTED",ToolInputEvidence.capture(write,Map.of("attackType","火"),"digest").get("coverage"));
        assertEquals("PARTIAL",ToolInputEvidence.capture(card("lookupPokemonTeamReference"),Map.of("queries",Collections.nCopies(7,"private-secret")),"digest").get("coverage"));
    }
}
