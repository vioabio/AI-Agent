package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.pokemon.reference.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.UserMessage;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TrainingResourceBudgetTest {
    @TempDir Path directory;
    TeamBuildCheckFacts.Report report(BuildCheckFixture f,List<String> moves,int count){
        var request=f.request();var original=request.build();
        var build=new BuildCheckFacts.Build(moves,original.ability(),original.item(),original.nature(),original.level(),original.evs(),original.ivs());
        var members=new ArrayList<TeamBuildCheckFacts.Member>();
        for(int i=1;i<=count;i++)members.add(new TeamBuildCheckFacts.Member(i,7,build));
        return PokemonTeamBuildCheckController.checkMembers(f.service,new TeamBuildCheckFacts.Request(request.dataReleaseId(),request.gameReleaseId(),members));
    }
    String text(TeamBuildCheckFacts.Report report,String conditions){return TrainingResourceBudget.describe(report,TrainingIntentState.resolve(List.of(),conditions));}
    @Test void duplicateMachinesAcrossMembersCountCopiesAndMaterialsWithoutInventoryAssumptions(){
        var f=new BuildCheckFixture(directory);var report=report(f,List.of("protect"),2);String before=report.toString();int calls=f.networkCalls.get();
        String answer=text(report,"资源：制作LP上限1000");
        assertTrue(answer.contains("TM007 × 2 份"));assertTrue(answer.contains("2 份，800 LP"));assertTrue(answer.contains("这不是实际缺口"));
        var recipe=MachineRecipeCatalog.read("protect","pokemon-scarlet-violet");
        for(var material:recipe.materials())assertTrue(answer.contains(material.name()+" × "+material.quantity()*2));
        assertEquals(before,report.toString());assertEquals(calls,f.networkCalls.get());
    }
    @Test void disputedAndAbsentRecipesAreExcludedFromKnownSubtotal(){
        var f=new BuildCheckFixture(directory);String answer=text(report(f,List.of("protect","water-pulse","surf"),1),"资源：制作LP上限100000");
        assertTrue(answer.contains("1 份，400 LP"));assertTrue(answer.contains("另有 2 个槽位"));
        assertTrue(answer.contains("不提供完整总价"));assertFalse(answer.contains("1500"));
        assertTrue(text(report(f,List.of("water-pulse","surf"),1),null).contains("没有来源一致的配方"));
    }
    @Test void budgetBoundariesAndMalformedLimitsDoNotBecomeBalanceOrPermission(){
        var f=new BuildCheckFixture(directory);var report=report(f,List.of("protect"),1);
        for(String limit:List.of("0","399"))assertTrue(text(report,"资源：制作LP上限"+limit).contains("已超过声明LP上限"));
        assertTrue(text(report,"资源：制作LP上限400").contains("仍不能据此认定整体预算充足"));
        for(String invalid:List.of("-1","1000000001","400.0","1000左右","0001"))
            assertFalse(text(report,"资源：制作LP上限"+invalid).contains("声明的制作LP上限："),invalid);
    }
    @Test void craftingRestrictionsAndAmbiguousItemRestrictionRemainExplicit(){
        var f=new BuildCheckFixture(directory);var report=report(f,List.of("protect"),1);
        for(String policy:List.of("不制作学习器","仅使用已有学习器")){
            String answer=text(report,"资源："+policy);assertTrue(answer.contains("本次不安排制作"));assertTrue(answer.contains("已有学习器和已学招式仍需确认"));
        }
        assertTrue(text(report,"资源：不使用道具").contains("尚未区分战斗用道具与培养学习器"));
        assertTrue(text(report,"资源：材料齐全").contains("不能据此认定背包与解锁已核验"));
    }
    @Test void conflictsAndMissingEvidenceNeverProduceFabricatedTotals(){
        var f=new BuildCheckFixture(directory);var report=report(f,List.of("protect"),1);
        var conflict=TrainingIntentState.resolve(List.of(new UserMessage("资源：不制作学习器")),"资源：制作LP上限1000");
        String answer=TrainingResourceBudget.describe(report,conflict);assertTrue(answer.contains("声明条件有冲突"));assertFalse(answer.contains("400 LP"));
        assertTrue(text(null,"资源：不制作学习器").contains("本次不计算份数、LP或材料"));
    }
    @Test void aggregationCoversAllSlotsEvenWhenPerMoveDetailsAreTruncatedAndCancellationPropagates(){
        var f=new BuildCheckFixture(directory);var report=report(f,f.request().build().moves(),6);
        String answer=text(report,null);assertTrue(answer.contains("共 24 个槽位"));assertTrue(answer.contains("另有 12 个槽位"));assertTrue(answer.contains("TM007 × 6 份"));
        Thread.currentThread().interrupt();try{assertThrows(java.util.concurrent.CancellationException.class,()->text(report,null));}finally{Thread.interrupted();}
    }
    @Test void alteredReportCannotSupplyBudgetNumbers(){
        var f=new BuildCheckFixture(directory);var report=report(f,List.of("protect"),1);
        var invalid=new TeamBuildCheckFacts.Report(report.schemaVersion(),report.request(),report.members(),report.counts(),report.duplicateFormSlots(),"VERIFIED",report.scope(),report.reportSha256());
        assertThrows(IllegalArgumentException.class,()->text(invalid,null));
    }
}
