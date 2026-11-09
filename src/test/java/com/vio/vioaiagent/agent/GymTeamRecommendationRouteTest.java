package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.pokemon.domain.TeamBuild;
import com.vio.vioaiagent.run.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GymTeamRecommendationRouteTest {
    private AgentRunContext context() {
        var run=AgentRun.create("r","s","c","owner","推荐应对莱姆的队伍","pokemon-scarlet-violet",
                "casual-singles","casual-v1","zh-CN",null,"pokeapi-reference-v1",null,null,"v1","trace",null,
                Instant.now().plusSeconds(30));
        return new AgentRunContext(run);
    }

    @Test void wrongGymEvidenceCannotBePublishedAsRymeConfiguration() {
        var fixture=new CandidateFixture("kofu");
        String answer=GymTeamRecommendationRoute.reply(context(),ToolCallbacks.from(fixture),
                new GymTurnPlan.Target("ryme","莱姆","initial",true)).orElseThrow();
        assertTrue(answer.contains("暂未生成配置"));
        assertFalse(answer.contains("| 月亮伊布 |"));
        assertEquals("ryme",fixture.requestedGym);assertEquals("pokeapi-reference-v1",fixture.requestedRelease);
    }

    @Test void partialObservedCandidatesRetainSourcesAssumptionsAndNoSaveBoundary() {
        var fixture=new CandidateFixture("ryme");
        String answer=GymTeamRecommendationRoute.reply(context(),ToolCallbacks.from(fixture),
                new GymTurnPlan.Target("ryme","莱姆","initial",true)).orElseThrow();
        assertTrue(answer.contains("莱姆初战"));assertTrue(answer.contains("部分建议"));
        assertTrue(answer.contains("共 3 位"));assertTrue(answer.contains("不是完整六人阵容"));
        assertTrue(answer.contains("月亮伊布"));assertTrue(answer.contains("大声咆哮 / 恶之波动 / 守住 / 帮助"));
        assertTrue(answer.contains("https://pokeapi.co/api/v2/pokemon/197/"));
        assertTrue(answer.contains("未核验捕获与进度"));assertTrue(answer.contains("没有保存、替换或修改 test1"));
        assertEquals("initial",fixture.requestedPhase);
    }

    @Test void mismatchedLevelCannotBeSilentlyRenderedAsLevelFifty() {
        var fixture=new CandidateFixture("ryme");fixture.level=20;
        String answer=GymTeamRecommendationRoute.reply(context(),ToolCallbacks.from(fixture),
                new GymTurnPlan.Target("ryme","莱姆","initial",true)).orElseThrow();
        assertTrue(answer.contains("暂未生成配置"));assertFalse(answer.contains("50 / 勤奋"));
    }

    @Test void missingRootSourcesCannotBePublishedAsVerifiedCandidates() {
        var fixture=new CandidateFixture("ryme");fixture.missingRootSources=true;
        String answer=GymTeamRecommendationRoute.reply(context(),ToolCallbacks.from(fixture),
                new GymTurnPlan.Target("ryme","莱姆","initial",true)).orElseThrow();
        assertTrue(answer.contains("暂未生成配置"));assertFalse(answer.contains("| 月亮伊布 |"));
    }

    public static class CandidateFixture {
        private final String outputGym;
        String requestedGym,requestedPhase,requestedRelease;
        int level=50;boolean missingRootSources;
        CandidateFixture(String outputGym){this.outputGym=outputGym;}
        @Tool(name="recommendGymTeamCore",description="Offline observed recommendation fixture")
        public Map<String,Object> recommendGymTeamCore(String gymId,String phase,String dataReleaseId) {
            requestedGym=gymId;requestedPhase=phase;requestedRelease=dataReleaseId;
            var candidates=new ArrayList<Map<String,Object>>();
            long[] ids={197,943,981};String[] names={"月亮伊布","獒教父","奇麒麟"};
            for(int i=0;i<ids.length;i++)candidates.add(Map.of("formId",ids[i],"name",names[i],
                    "configuration",new TeamBuild(List.of("snarl","dark-pulse","protect","helping-hand"),"synchronize","","hardy",level,null,null),
                    "abilityLabel","同步","moveLabels",List.of("大声咆哮","恶之波动","守住","帮助"),
                    "sources",List.of("https://pokeapi.co/api/v2/pokemon/"+ids[i]+"/")));
            return Map.of("gymId",outputGym,"phase",phase,"status","PARTIAL","candidates",candidates,
                    "sources",missingRootSources?List.of():List.of("https://pokeapi.co/api/v2/pokemon/197/","https://pokeapi.co/api/v2/pokemon/943/","https://pokeapi.co/api/v2/pokemon/981/"),
                    "missing",List.of("未核验捕获与进度、实际学习材料和服从条件。"));
        }
    }
}
