package com.vio.vioaiagent.tools.governance;

import com.vio.vioaiagent.pokemon.engine.*;
import com.vio.vioaiagent.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DamageToolSchemaTest {
    @Test void nullDefaultContainersReachTheActualDtoWhileIdentityFieldsRemainRequired() {
        var engine=mock(LocalDamageEngine.class);var callback=DamageToolSchema.adapt(ToolCallbacks.from(new DamageEngineController.Tools(engine,null))[0]);
        var hitl=new HitlManager();var masker=new ParameterMasker();
        var gateway=new ToolExecutionGateway(hitl,new PathGuard(List.of(System.getProperty("user.dir")+"/tmp")),new CommandGuard(List.of()),masker,new AuditLogger(masker));
        try {
            gateway.wrapMcp(callback,ToolCapabilityCatalog.find("calculateBattleDamage").orElseThrow());
            String input="{\"request\":{\"generation\":9,\"dataReleaseId\":\"pokeapi-reference-v1\",\"attacker\":{\"species\":\"squirtle\",\"build\":null},\"defender\":{\"species\":\"charmander\",\"build\":null},\"move\":\"surf\",\"field\":null}}";
            assertEquals(ToolGovernanceResult.Status.EXECUTED,gateway.execute("calculateBattleDamage",input,new ToolExecutionContext("owner","r",Set.of("trainer"),"t",null,null)).status());
            verify(engine).calculate(argThat(r->r.field()==null&&r.attacker().build().level()==50&&r.attacker().build().ivs().hp()==31));
            clearInvocations(engine);
            assertEquals(ToolGovernanceResult.Status.REJECTED,gateway.execute("calculateBattleDamage",input.replace("\"generation\":9,",""),new ToolExecutionContext("owner","r2",Set.of("trainer"),"t",null,null)).status());
            assertEquals(ToolGovernanceResult.Status.REJECTED,gateway.execute("calculateBattleDamage",input.replaceFirst("\"build\":null","\"build\":{\"evs\":{\"hp\":4}}"),new ToolExecutionContext("owner","r3",Set.of("trainer"),"t",null,null)).status());
            verifyNoInteractions(engine);
        }finally{gateway.shutdown();hitl.shutdown();}
    }
    @Test void partialStatObjectsDoNotBecomeAnImplicitBuildConfiguration() throws Exception {
        var engine=mock(LocalDamageEngine.class);var original=ToolCallbacks.from(new DamageEngineController.Tools(engine,null))[0];
        var callback=DamageToolSchema.adapt(original);
        var schema=new com.fasterxml.jackson.databind.ObjectMapper().readTree(callback.getToolDefinition().inputSchema());
        var evs=schema.path("properties").path("request").path("properties").path("attacker").path("properties").path("build").path("properties").path("evs");
        assertEquals(6,evs.path("required").size());assertTrue(schema.path("properties").path("request").path("required").toString().contains("generation"));
        assertFalse(schema.toString().contains("\"$ref\""));
    }
}
