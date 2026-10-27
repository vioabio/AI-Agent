package com.vio.vioaiagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.ai.tool.*;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvaluationToolPolicyTest {
    MockEnvironment env(String mode) {
        var e=new MockEnvironment().withProperty("vio.evaluation.tool-mode",mode).withProperty("vio.local-accounts.enabled","true");
        e.setActiveProfiles("local","evaluation");return e;
    }
    ToolCallback tool(String name){var t=mock(ToolCallback.class);when(t.getToolDefinition()).thenReturn(ToolDefinition.builder().name(name).description("test").inputSchema("{}").build());return t;}
    @Test void defaultOffPreservesRegisteredToolsAndDoesNotRequireEvaluationProfile() {
        var p=new EvaluationToolPolicy(new MockEnvironment());var tools=new ToolCallback[]{tool("previewTeamChanges")};assertFalse(p.active());assertArrayEquals(tools,p.select(tools));
    }
    @Test void modesOnlySelectDeclaredReadOnlyTools() {
        var tools=new ToolCallback[]{tool("previewTeamChanges"),tool("terminalOperation"),tool("searchWeb"),tool("searchPokemonGuides"),tool("lookupPokemonReference"),tool("calculateBattleDamage"),tool("lookupPokemonLearnset"),tool("lookupPokemonMachine"),tool("lookupPokemonMechanics"),tool("checkPokemonBuild"),tool("checkPokemonTeamBuild")};
        assertEquals(0,new EvaluationToolPolicy(env("model-only")).select(tools).length);
        assertEquals(List.of("searchPokemonGuides"),Arrays.stream(new EvaluationToolPolicy(env("guides-only")).select(tools)).map(t->t.getToolDefinition().name()).toList());
        var selected=new EvaluationToolPolicy(env("read-only-tools")).select(tools);assertEquals(8,selected.length);
        assertTrue(Arrays.stream(selected).noneMatch(t->Set.of("previewTeamChanges","terminalOperation","searchWeb").contains(t.getToolDefinition().name())));
    }
    @Test void incompatibleProfilesAndUnsafeSettingsFailRatherThanSilentlyEnable() {
        for(var profiles:List.of(new String[]{"local"},new String[]{"evaluation"},new String[]{"local","evaluation","prod"},new String[]{"local","evaluation","production"},new String[]{"local","evaluation","demo"})) {
            var e=env("model-only");e.setActiveProfiles(profiles);assertThrows(IllegalStateException.class,()->new EvaluationToolPolicy(e));
        }
        for(String property:List.of("vio.agent.v1.enabled","vio.run.durable-resume","spring.ai.mcp.client.enabled","vio.tools.high-risk.enabled"))
            assertThrows(IllegalStateException.class,()->new EvaluationToolPolicy(env("model-only").withProperty(property,"true")));
        assertThrows(IllegalStateException.class,()->new EvaluationToolPolicy(env("model-only").withProperty("vio.local-accounts.enabled","false")));
        assertThrows(IllegalArgumentException.class,()->new EvaluationToolPolicy(env("unknown")));
    }
    @Test void missingRequiredToolsCannotBeMisreportedAsATreatmentArm() {
        assertThrows(IllegalStateException.class,()->new EvaluationToolPolicy(env("guides-only")).select(new ToolCallback[0]));
        assertThrows(IllegalStateException.class,()->new EvaluationToolPolicy(env("read-only-tools")).select(new ToolCallback[]{tool("searchPokemonGuides")}));
    }
    @Test void actualSpringContainerWiresBothNormalAndExplicitEvaluationConfigurations() {
        try(var c=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            c.register(EvaluationToolPolicy.class);c.refresh();assertEquals(EvaluationToolPolicy.Mode.OFF,c.getBean(EvaluationToolPolicy.class).mode());
        }
        try(var c=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            c.setEnvironment(env("model-only"));c.register(EvaluationToolPolicy.class);c.refresh();assertTrue(c.getBean(EvaluationToolPolicy.class).active());
        }
    }
}
