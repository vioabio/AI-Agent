package com.vio.vioaiagent.tools.governance;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vio.vioaiagent.pokemon.engine.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import com.vio.vioaiagent.security.*;
import org.springframework.ai.tool.*;
import org.springframework.ai.tool.definition.ToolDefinition;

class DamageResultEvidenceTest {
    private final ObjectMapper json=new ObjectMapper();
    private ToolCapabilityCard card(){return ToolCapabilityCatalog.find("calculateBattleDamage").orElseThrow();}
    private JsonNode fixture() throws Exception{return json.readTree(Path.of("evaluation/campus/s01/damage-reference.json").toFile());}
    private ObjectNode validated(JsonNode fixture){var result=(ObjectNode)fixture.path("result").deepCopy();var request=json.convertValue(fixture.path("input"),LocalDamageEngine.Request.class);result.set("conditionValidation",DamageResultContract.verify(request,request,result,List.of("torrent"),List.of("blaze")));return result;}
    @Test void outputEvidenceLinksSuppliedAndResolvedConditionsWithoutCopyingNames() throws Exception {
        var fixture=fixture();var result=validated(fixture);Map<String,Object> args=Map.of("request",json.convertValue(fixture.path("input"),Map.class));
        var proof=DamageResultEvidence.capture(card(),args,"digest",result.toString());assertEquals("VERIFIED",proof.get("status"));
        assertFalse(proof.toString().contains("Squirtle"));assertEquals(115,proof.get("defenderHp"));assertTrue(((List<?>)proof.get("omittedFields")).isEmpty());
        assertEquals(List.of("/request/field/weather","/request/field/terrain").stream().sorted().toList(),((List<String>)proof.get("explicitNullFields")).stream().sorted().toList());
    }
    @Test void changedRequestProjectionOrMissingValidationDoesNotBecomeEvidence() throws Exception {
        var fixture=fixture();var result=validated(fixture);Map<String,Object> args=Map.of("request",json.convertValue(fixture.path("input"),Map.class));
        ((ObjectNode)result.path("assumptions").path("attacker")).put("level",60);assertEquals("UNAVAILABLE",DamageResultEvidence.capture(card(),args,"digest",result.toString()).get("status"));
        var changed=(ObjectNode)fixture.path("input").deepCopy();((ObjectNode)changed.path("attacker").path("build")).put("level",60);
        assertEquals("UNAVAILABLE",DamageResultEvidence.capture(card(),Map.of("request",json.convertValue(changed,Map.class)),"digest",validated(fixture).toString()).get("status"));
        assertEquals("UNAVAILABLE",DamageResultEvidence.capture(card(),args,"digest",fixture.path("result").toString()).get("status"));
    }
    @Test void gatewayAndCacheKeepOneResultBoundToTheSameActualInput() throws Exception {
        var fixture=fixture();String output=validated(fixture).toString(),input=json.writeValueAsString(Map.of("request",fixture.path("input")));
        var hitl=new HitlManager();var masker=new ParameterMasker();var calls=new java.util.concurrent.atomic.AtomicInteger();
        var gateway=new ToolExecutionGateway(hitl,new PathGuard(List.of(System.getProperty("user.dir")+"/tmp")),new CommandGuard(List.of()),masker,new AuditLogger(masker));
        try {
            var callback=new ToolCallback(){public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name("calculateBattleDamage").description("fixture").inputSchema("{}").build();}
                public String call(String raw){calls.incrementAndGet();return output;}};
            gateway.wrapMcp(callback,card());var events=new ArrayList<ToolGovernanceEvent>();var context=new ToolExecutionContext("owner","run",Set.of("trainer"),"trace","same-key",events::add);
            assertEquals(ToolGovernanceResult.Status.EXECUTED,gateway.execute("calculateBattleDamage",input,context).status());
            var finish=events.stream().filter(e->e.eventType().equals("tool.finished")).findFirst().orElseThrow();var proof=(Map<?,?>)finish.attributes().get("outputEvidence");
            assertEquals("VERIFIED",proof.get("status"));assertEquals(finish.attributes().get("parameterDigest"),proof.get("parameterDigest"));
            assertEquals(ToolGovernanceResult.Status.DEDUPLICATED,gateway.execute("calculateBattleDamage",input,context).status());
            assertEquals(proof,events.getLast().attributes().get("outputEvidence"));assertEquals(1,calls.get());
        }finally{gateway.shutdown();hitl.shutdown();}
    }
}
