package com.vio.vioaiagent.agent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
class TaskSkillRegistryTest {
    @Test void loadsOnlyRegisteredReadOnlyTasks() {
        var registry=new TaskSkillRegistry();assertEquals(5,registry.metadata().size());
        var skill=registry.select("我的队伍如何应对馆主",SpecialistRole.TEAM_PLANNING).orElseThrow();
        assertEquals("gym-challenge",skill.id());assertTrue(registry.instruction(skill).contains("不宣称必胜"));
        assertFalse(registry.permitted(skill,SpecialistRole.TEAM_PLANNING).contains("previewTeamChanges"));
        assertTrue(registry.permitted(skill,SpecialistRole.POKEMON_RESEARCH).isEmpty());
    }
    @Test void ordinaryRequestsDoNotLoadUnrelatedTask() {
        assertTrue(new TaskSkillRegistry().select("你好",SpecialistRole.POKEMON_RESEARCH).isEmpty());
    }
    @Test void unregisteredMetadataCannotReadAnArbitraryResource() {
        var r=new TaskSkillRegistry();var unknown=new TaskSkillRegistry.Metadata("unknown","1","测试","POKEMON_RESEARCH",Set.of("searchWeb"));
        assertThrows(IllegalArgumentException.class,()->r.instruction(unknown));
        assertThrows(IllegalArgumentException.class,()->new TaskSkillRegistry.Metadata("../secret","1","测试","POKEMON_RESEARCH",Set.of("searchWeb")));
    }
}
