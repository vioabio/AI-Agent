package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vio.vioaiagent.pokemon.reference.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SavedTrainingResourcesTest {
    @TempDir Path directory;
    TeamBuildCheckFacts.Report report(BuildCheckFixture f,int count){
        var b=f.request();var members=new ArrayList<TeamBuildCheckFacts.Member>();
        for(int i=1;i<=count;i++)members.add(new TeamBuildCheckFacts.Member(i,b.pokemonId(),b.build()));
        return PokemonTeamBuildCheckController.checkMembers(f.service,new TeamBuildCheckFacts.Request(b.dataReleaseId(),b.gameReleaseId(),members));
    }
    @Test void actualLearningCrossesPinnedIdentifiersRecipesAndRegionsWithUnknowns(){
        var f=new BuildCheckFixture(directory);String text=SavedTrainingResources.describe(report(f,1));
        assertTrue(text.contains("TM007"));assertTrue(text.contains("400 LP"));assertTrue(text.contains("配方参考（社区资料"));
        assertTrue(text.contains("获取区域线索（社区参考"));assertTrue(text.contains("2026-10-27"));assertTrue(text.contains("目录未收录"));
        assertTrue(text.contains("实际剧情解锁、材料库存、精确坐标"));assertTrue(text.contains("本次未获取物品"));
        assertFalse(text.contains("1500 LP"),"A disputed water-pulse recipe must not publish one source's quantities");
        // Conflict records never borrow one source's quantities as an agreed recipe.
        for(String move:List.of("water-pulse","ice-beam","protect")){
            var recipe=MachineRecipeCatalog.read(move,"pokemon-scarlet-violet");
            if(!"CONSISTENT_REFERENCE".equals(recipe.status()))assertTrue(text.contains("来源差异待核对，本次不提供材料或数量"));
        }
    }
    @Test void expansionIsBoundedPrioritizesCataloguedRecordsAndReportsOmissions(){
        var f=new BuildCheckFixture(directory);String text=SavedTrainingResources.describe(report(f,2));
        assertEquals(4,text.split("#### 第 ",-1).length-1);
        assertTrue(text.contains("另有 4 条学习器记录未展开"));assertFalse(text.contains("surf"));
    }
    @Test void absentCorruptAndCancelledEvidenceNeverProducesResourceNumbers(){
        assertFalse(SavedTrainingResources.describe(null).contains("TM007"));
        var f=new BuildCheckFixture(directory);ObjectNode changed=f.json.valueToTree(report(f,1));changed.put("jointStatus","VERIFIED");
        assertThrows(IllegalArgumentException.class,()->SavedTrainingResources.describe(f.json.treeToValue(changed,TeamBuildCheckFacts.Report.class)));
        var valid=report(f,1);Thread.currentThread().interrupt();
        try{assertThrows(java.util.concurrent.CancellationException.class,()->SavedTrainingResources.describe(valid));}finally{Thread.interrupted();}
    }
}
