package com.vio.vioaiagent.agent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
class TaskSkillRegistryTest {
    @Test void generalTeamPlanningDoesNotExposeSavedTeamReads(){
        var registry=new TaskSkillRegistry();
        for(String question:java.util.List.of("我想围绕一个核心组成队伍，如何检查功能角色是否重复？","当前有哪些组队思路","请给一支队伍的搭配建议")){
            var skill=registry.select(question,SpecialistRole.TEAM_PLANNING).orElseThrow();
            assertEquals("team-core-planning",skill.id());
            var tools=registry.permitted(skill,SpecialistRole.TEAM_PLANNING);
            assertTrue(tools.contains("searchPokemonGuides"));assertFalse(tools.contains("listCurrentTeams"));assertFalse(tools.contains("readCurrentTeam"));
        }
        assertEquals("saved-team-review",registry.select("分析我的队伍",SpecialistRole.TEAM_PLANNING).orElseThrow().id());
    }
    @Test void englishWordsContainingOuDoNotSelectCompetitiveResearch(){
        var registry=new TaskSkillRegistry();
        for(String question:java.util.List.of("Could you explain 宝可梦培养？","Tell me about Pokémon","查看soul资料","查询OUter资料","查询scout资料")){
            var selected=registry.select(question,SpecialistRole.POKEMON_RESEARCH);
            assertFalse(selected.map(m->m.id().equals("competitive-team-reference")).orElse(false),question);
        }
        assertEquals("training-build-review",registry.select("Could you explain 宝可梦培养？",SpecialistRole.POKEMON_RESEARCH).orElseThrow().id());
        for(String question:java.util.List.of("朱紫OU单打","gen9ou配置","SV OU teams","查询ou样例","当前热门队伍"))
            assertEquals("competitive-team-reference",registry.select(question,SpecialistRole.POKEMON_RESEARCH).orElseThrow().id(),question);
    }
    @Test void namedGymSelectsChallengeToolsAndNegatedNameDoesNot(){
        var registry=new TaskSkillRegistry();
        for(String question:java.util.List.of("我的队伍怎么应对奇树初战","给我一支对付阿枫的队伍","team against Iono")){
            var skill=registry.select(question,SpecialistRole.TEAM_PLANNING).orElseThrow();
            assertEquals("gym-challenge",skill.id());
            assertTrue(registry.permitted(skill,SpecialistRole.TEAM_PLANNING).contains("analyzeSavedTeamForGym"));
        }
        assertEquals("saved-team-review",registry.select("不是奇树，分析我的队伍",SpecialistRole.TEAM_PLANNING).orElseThrow().id());
    }
    @Test void emptyOrInvalidSelectionDoesNotInventATask(){
        var registry=new TaskSkillRegistry();
        assertTrue(registry.select("",SpecialistRole.DAMAGE_CALCULATION).isEmpty());
        assertTrue(registry.select("分析队伍",null).isEmpty());
        assertTrue(registry.select("长".repeat(8001),SpecialistRole.TEAM_PLANNING).isEmpty());
    }
    @Test void loadsOnlyRegisteredReadOnlyTasks() {
        var registry=new TaskSkillRegistry();assertEquals(6,registry.metadata().size());
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
