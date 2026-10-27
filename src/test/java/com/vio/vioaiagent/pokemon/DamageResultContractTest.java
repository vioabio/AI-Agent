package com.vio.vioaiagent.pokemon;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vio.vioaiagent.pokemon.engine.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DamageResultContractTest {
    private final ObjectMapper json=new ObjectMapper();
    private JsonNode fixture() throws Exception{return json.readTree(Path.of("evaluation/campus/s01/damage-reference.json").toFile());}
    private LocalDamageEngine.Request request(JsonNode fixture){return json.convertValue(fixture.path("input"),LocalDamageEngine.Request.class);}
    private ObjectNode verify(LocalDamageEngine.Request request,JsonNode result){return DamageResultContract.verify(request,request,result,List.of("torrent"),List.of("blaze"));}
    @Test void matchesKnownResultAndBindsTypedRequestAndValidatedProjection() throws Exception {
        var fixture=fixture();var request=request(fixture);var validation=verify(request,fixture.path("result"));
        assertEquals("VERIFIED",validation.path("status").asText());assertEquals(0,validation.path("engineDefaultAbilitySides").size());
        assertEquals(DamageResultContract.digest(request),validation.path("typedRequestDigest").asText());
        assertEquals(DamageResultContract.projectionDigest(fixture.path("result")),validation.path("validatedProjectionDigest").asText());
    }
    @Test void plausibleResultsForAnotherSpeciesLevelMoveOrFieldCannotPass() throws Exception {
        var fixture=fixture();var expected=request(fixture);
        for(String path:List.of("species","level","nature","ability","item")) {
            var result=fixture.path("result").deepCopy();var member=(ObjectNode)result.path("assumptions").path("attacker");
            if(path.equals("level"))member.put(path,60);else member.put(path,"different");
            assertThrows(IllegalStateException.class,()->verify(expected,result),path);
        }
        var move=fixture.path("result").deepCopy();((ObjectNode)move.path("assumptions")).put("move","Tackle");assertThrows(IllegalStateException.class,()->verify(expected,move));
        var weather=fixture.path("result").deepCopy();((ObjectNode)weather.path("assumptions").path("field")).put("weather","Rain");assertThrows(IllegalStateException.class,()->verify(expected,weather));
        var evs=fixture.path("result").deepCopy();((ObjectNode)evs.path("assumptions").path("defender").path("evs")).put("spa",0);assertThrows(IllegalStateException.class,()->verify(expected,evs));
        var omittedItem=fixture.path("result").deepCopy();((ObjectNode)omittedItem.path("assumptions").path("attacker")).remove("item");assertThrows(IllegalStateException.class,()->verify(expected,omittedItem));
    }
    @Test void selfConsistentPercentagesCannotHideWrongHpOrNonIntegerConditions() throws Exception {
        var fixture=fixture();var expected=request(fixture);var result=(ObjectNode)fixture.path("result").deepCopy();result.put("defenderHp",116);
        var percent=(ObjectNode)result.path("percent");percent.put("minimum",100.0*result.path("range").path("minimum").asInt()/116);percent.put("maximum",100.0*result.path("range").path("maximum").asInt()/116);
        assertThrows(IllegalStateException.class,()->verify(expected,result));
        var nonInteger=fixture.path("result").deepCopy();((ObjectNode)nonInteger.path("assumptions").path("attacker")).put("level","50");assertThrows(IllegalStateException.class,()->verify(expected,nonInteger));
        var missingField=fixture.path("result").deepCopy();((ObjectNode)missingField.path("assumptions").path("field")).remove("weather");assertThrows(IllegalStateException.class,()->verify(expected,missingField));
    }
    @Test void defaultAbilityMustBeKnownAndIsReportedWithoutClaimingUserSelection() throws Exception {
        var fixture=(ObjectNode)fixture();((ObjectNode)fixture.path("input").path("attacker").path("build")).put("ability","");var request=request(fixture);
        assertEquals("attacker",verify(request,fixture.path("result")).path("engineDefaultAbilitySides").get(0).asText());
        assertThrows(IllegalStateException.class,()->DamageResultContract.verify(request,request,fixture.path("result"),List.of(),List.of("blaze")));
    }
    @Test void normalizedSpeciesAliasBindsOriginalTypedIdentifier() throws Exception {
        var fixture=fixture();var normalized=request(fixture);var original=new LocalDamageEngine.Request(9,normalized.dataReleaseId(),new LocalDamageEngine.Combatant("7",normalized.attacker().build()),normalized.defender(),"SURF",normalized.field());
        var validation=DamageResultContract.verify(original,normalized,fixture.path("result"),List.of("torrent"),List.of("blaze"));
        assertEquals(DamageResultContract.digest(original),validation.path("typedRequestDigest").asText());assertNotEquals(DamageResultContract.digest(normalized),validation.path("typedRequestDigest").asText());
    }
}
